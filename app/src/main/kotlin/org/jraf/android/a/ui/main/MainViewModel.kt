/*
 * This source is part of the
 *      _____  ___   ____
 *  __ / / _ \/ _ | / __/___  _______ _
 * / // / , _/ __ |/ _/_/ _ \/ __/ _ `/
 * \___/_/|_/_/ |_/_/ (_)___/_/  \_, /
 *                              /___/
 * repository.
 *
 * Copyright (C) 2022-present Benoit 'BoD' Lubek (BoD@JRAF.org)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */
package org.jraf.android.a.ui.main

import android.app.Application
import android.app.SearchManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Drawable
import android.os.Build
import android.os.UserHandle
import android.provider.ContactsContract
import android.provider.Settings
import androidx.appcompat.content.res.AppCompatResources
import androidx.core.net.toUri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import org.jraf.android.a.R
import org.jraf.android.a.data.AppRepository
import org.jraf.android.a.data.ContactRepository
import org.jraf.android.a.data.LaunchItemRepository
import org.jraf.android.a.data.LaunchItemRepository.Counter
import org.jraf.android.a.data.NotificationRepository
import org.jraf.android.a.data.SettingsRepository
import org.jraf.android.a.data.ShortcutRepository
import org.jraf.android.a.get
import org.jraf.android.a.notification.NotificationListenerService
import org.jraf.android.a.ui.settings.SettingsActivity
import org.jraf.android.a.util.containsIgnoreAccents
import org.jraf.android.a.util.invoke
import org.jraf.android.a.util.signalStateFlow

class MainViewModel(application: Application) : AndroidViewModel(application) {
    companion object {
        const val MOST_USED_ITEMS_COUNT = 5
    }

    private val launchItemRepository = application[LaunchItemRepository]
    private val appRepository = application[AppRepository]
    private val contactRepository = application[ContactRepository]
    private val shortcutRepository = application[ShortcutRepository]
    private val notificationRepository = application[NotificationRepository]
    private val settingsRepository = application[SettingsRepository]

    private val ignoredNotificationsItems = launchItemRepository.getIgnoredNotificationsItems()
    private val renamedItems = launchItemRepository.getRenamedItems()

    @OptIn(ExperimentalCoroutinesApi::class)
    private val allLaunchItems: Flow<List<LaunchItem>> = appRepository.allApps.flatMapLatest { allApps ->
        val allShortcutsFlow = shortcutRepository.getAllShortcuts(allApps.map { it.componentName.getPackageName() })
        combine(
            allShortcutsFlow,
            contactRepository.starredContacts,
            notificationRepository.notificationRankings,
            // combine() accepts at most 5 flows, so we "overcombine"
            combine(
                ignoredNotificationsItems,
                renamedItems,
            ) { ignoredNotificationsItems, renamedItems -> ignoredNotificationsItems to renamedItems },
            hasNotificationListenerPermission,
        ) { allShortcuts, starredContacts, notificationRankings, (ignoredNotificationsItems, renamedItems), hasNotificationListenerPermission ->
            allApps.map { app ->
                val id = AppLaunchItem.getId(component = app.componentName.toString(), user = app.user.toString())
                val ignoreNotifications = id in ignoredNotificationsItems
                val label = renamedItems[id]
                val notificationRanking = if (!hasNotificationListenerPermission || ignoreNotifications) {
                    null
                } else {
                    notificationRankings[app.componentName.getPackageName()]
                }
                app.toAppLaunchItem(
                    ignoreNotifications = ignoreNotifications,
                    notificationRanking = notificationRanking,
                    label = label,
                )
            } +
                    allShortcuts.map {
                        val id = ShortcutLaunchItem.getId(it.id)
                        val label = renamedItems[id]
                        it.toShortcutLaunchItem(label = label)
                    } +
                    starredContacts.map { it.toContactLaunchItem() } +
                    ASettingsLaunchItem(context = getApplication(), isDeprioritized = false)
        }
    }


    private val deletedLaunchItems = launchItemRepository.getDeletedItems()

    val searchQuery = MutableStateFlow("")
    val filteredLaunchItems: StateFlow<List<LaunchItem>> =
        combine(
            allLaunchItems,
            searchQuery,
            launchItemRepository.counters,
            deletedLaunchItems,
        ) { allLaunchedItems, searchQuery, counters, deletedLaunchItems ->
            val query = searchQuery.trim()
            val filteredItems = allLaunchedItems
                .map {
                    if (it is AppLaunchItem && counters[it.id] is Counter.Deprioritized) {
                        it.copy(isDeprioritized = true)
                    } else if (it is ASettingsLaunchItem && counters[it.id] is Counter.Deprioritized) {
                        it.copy(isDeprioritized = true)
                    } else {
                        it
                    }
                }
                .filterNot { it.id in deletedLaunchItems }
                .filter { it.matchesFilter(query) }

            val mostUsedItems = filteredItems
                // Get the most used items, sorted by counter long term, without short term boost (descending)
                .sortedByDescending {
                    when (val counter = counters[it.id]) {
                        is Counter.ShortAndLongTerm -> counter.longTerm
                        is Counter.Deprioritized -> -1
                        null -> 0
                    }
                }.take(MOST_USED_ITEMS_COUNT)

            // Add the most used items at the top
            (mostUsedItems +
                    // Sort the rest by counter with short term boost (descending)
                    (filteredItems - mostUsedItems)
                        .sortedByDescending {
                            when (val counter = counters[it.id]) {
                                is Counter.ShortAndLongTerm -> counter.combined
                                is Counter.Deprioritized -> -1
                                null -> 0
                            }
                        }
                    )
                // Then sort by notification rank (ascending)
                .sortedBy {
                    if (it.notificationRanking != null && !it.ignoreNotifications) {
                        it.notificationRanking!!
                    } else {
                        Integer.MAX_VALUE
                    }
                }
        }
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val isKeyboardWebSearchActive: Flow<Boolean> = combine(filteredLaunchItems, searchQuery) { launchItems, query ->
        launchItems.isEmpty() && query.isNotBlank()
    }

    val intentToStart = MutableSharedFlow<Pair<Intent, UserHandle?>>(extraBufferCapacity = 1)
    val onScrollUp = signalStateFlow()

    val shouldShowRequestPermissionRationale = MutableStateFlow(false)

    val hasNotifications: StateFlow<Boolean> = notificationRepository.notificationRankings
        .map { it.isNotEmpty() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    fun onSearchQueryChange(query: String) {
        searchQuery.value = query
        onScrollUp()
    }

    fun onLaunchItemAction1(launchedItem: LaunchItem) {
        when (launchedItem) {
            is AppLaunchItem -> intentToStart.tryEmit(launchedItem.launchAppIntentUser)
            is ASettingsLaunchItem -> intentToStart.tryEmit(Pair(launchedItem.launchAppIntent, null))
            is ContactLaunchItem -> intentToStart.tryEmit(Pair(launchedItem.viewContactIntent, null))
            is ShortcutLaunchItem -> shortcutRepository.launchShortcut(launchedItem.shortcut)
        }
        // Don't record the launch if there's a notification, as it's likely the user just wants to read it, that shouldn't count as a "real" launch
        if (!launchedItem.hasNotification) {
            viewModelScope.launch {
                // Add a delay so the reordering animation isn't distracting
                delay(1000)
                launchItemRepository.recordLaunchedItem(launchedItem.id)
            }
        }
    }

    fun onLaunchItemAction2(launchedItem: LaunchItem) {
        when (launchedItem) {
            is AppLaunchItem -> intentToStart.tryEmit(Pair(launchedItem.launchAppDetailsIntent, null)) // TODO: launch details for user
            is ASettingsLaunchItem -> intentToStart.tryEmit(Pair(launchedItem.launchAppDetailsIntent, null))
            is ContactLaunchItem -> {
                launchedItem.sendSmsIntent?.let { intentToStart.tryEmit(Pair(it, null)) }

                // Long clicking on a contact counts as a primary action
                viewModelScope.launch {
                    delay(1000)
                    launchItemRepository.recordLaunchedItem(launchedItem.id)
                }
            }

            is ShortcutLaunchItem -> viewModelScope.launch { launchItemRepository.deleteItem(launchedItem.id) }
        }
    }

    fun onLaunchItemAction3(launchedItem: LaunchItem) {
        viewModelScope.launch {
            if (launchedItem.isDeprioritized) {
                launchItemRepository.undeprioritizeItem(launchedItem.id)
            } else {
                launchItemRepository.deprioritizeItem(launchedItem.id)
            }
        }
    }

    fun onLaunchItemAction4(launchedItem: LaunchItem) {
        viewModelScope.launch {
            if (launchedItem.ignoreNotifications) {
                launchItemRepository.unignoreNotifications(launchedItem.id)
            } else {
                launchItemRepository.ignoreNotifications(launchedItem.id)
            }
        }
    }

    fun onRenameLaunchItem(launchedItem: LaunchItem, label: String?) {
        viewModelScope.launch {
            if (label == null) {
                launchItemRepository.unrenameItem(launchedItem.id)
            } else {
                launchItemRepository.renameItem(launchedItem.id, label)
            }
        }
    }

    fun resetSearchQuery() {
        onSearchQueryChange("")
    }

    fun onWebSearchClick() {
        val intent = Intent(Intent.ACTION_WEB_SEARCH)
            .putExtra(SearchManager.QUERY, searchQuery.value)
        intentToStart.tryEmit(Pair(intent, null))
    }

    fun onKeyboardActionButtonClick() {
        val launchItems = filteredLaunchItems.value
        if (launchItems.isEmpty()) {
            onWebSearchClick()
        } else {
            onLaunchItemAction1(launchItems.first())
        }
    }

    sealed class LaunchItem {
        abstract val label: String
        abstract val drawable: Drawable
        abstract val id: String
        abstract val isDeprioritized: Boolean
        abstract val isRenamed: Boolean
        abstract val ignoreNotifications: Boolean
        abstract val notificationRanking: Int?

        val hasNotification: Boolean get() = notificationRanking != null

        abstract fun matchesFilter(query: String): Boolean
    }

    data class AppLaunchItem(
        override val label: String,
        private val componentName: ComponentName,
        private val user: UserHandle?,
        override val drawable: Drawable,
        override val isDeprioritized: Boolean,
        override val isRenamed: Boolean,
        override val ignoreNotifications: Boolean,
        override val notificationRanking: Int?,
    ) : LaunchItem() {
        override val id = getId(componentName.toString(), user.toString())

        val launchAppIntentUser: Pair<Intent, UserHandle?>
            get() =
                Pair(Intent()
                .setComponent(componentName)
                .setAction(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED), user)


        val launchAppDetailsIntent: Intent
            get() = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData("package:${componentName.getPackageName()}".toUri())

        override fun matchesFilter(query: String): Boolean {
            return label.containsIgnoreAccents(query) ||
                    componentName.getPackageName().contains(query, true)
        }

        companion object {
            fun getId(component: String, user: String) = "${user}/${component}"
        }
    }

    private fun AppRepository.App.toAppLaunchItem(
        ignoreNotifications: Boolean,
        notificationRanking: Int?,
        label: String?,
    ): AppLaunchItem {
        return AppLaunchItem(
            label = label ?: this.label,
            componentName = componentName,
            user = user,
            drawable = drawable,
            isDeprioritized = false,
            isRenamed = label != null,
            ignoreNotifications = ignoreNotifications,
            notificationRanking = notificationRanking,
        )
    }

    data class ContactLaunchItem(
        override val label: String,
        private val contactId: Long,
        private val lookupKey: String,
        override val drawable: Drawable,
        private val phoneNumber: String?,
    ) : LaunchItem() {
        override val id = lookupKey

        val viewContactIntent: Intent
            get() = Intent(Intent.ACTION_VIEW)
                .setData(ContactsContract.Contacts.getLookupUri(contactId, lookupKey))

        val sendSmsIntent: Intent?
            get() = phoneNumber?.let {
                Intent(Intent.ACTION_SENDTO)
                    .setData("smsto:$it".toUri())
            }

        override fun matchesFilter(query: String): Boolean {
            return label.containsIgnoreAccents(query)
        }

        override val isDeprioritized: Boolean = false

        override val isRenamed: Boolean = false

        override val ignoreNotifications: Boolean = false

        override val notificationRanking: Int? = null
    }

    private fun ContactRepository.Contact.toContactLaunchItem(): ContactLaunchItem {
        return ContactLaunchItem(
            label = displayName,
            contactId = contactId,
            lookupKey = lookupKey,
            drawable = photoDrawable,
            phoneNumber = phoneNumber,
        )
    }

    data class ShortcutLaunchItem(
        override val label: String,
        override val drawable: Drawable,
        override val isRenamed: Boolean,
        val shortcut: ShortcutRepository.Shortcut,
    ) : LaunchItem() {
        override val id = getId(shortcut.id)

        override fun matchesFilter(query: String): Boolean {
            return label.containsIgnoreAccents(query)
        }

        override val isDeprioritized: Boolean = false

        override val ignoreNotifications: Boolean = false

        override val notificationRanking: Int? = null

        companion object {
            fun getId(id: String) = ShortcutRepository.getId(id)
        }
    }

    data class ASettingsLaunchItem(
        val context: Context,
        override val isDeprioritized: Boolean,
    ) : LaunchItem() {
        override val drawable: Drawable = AppCompatResources.getDrawable(context, R.mipmap.ic_launcher)!!
        override val id = "aSettings"
        override val label: String = context.getString(R.string.settings_title)
        override val ignoreNotifications: Boolean = false
        override val notificationRanking: Int? = null
        override val isRenamed: Boolean = false
        val launchAppIntent: Intent = Intent(context, SettingsActivity::class.java)
        val launchAppDetailsIntent: Intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData("package:${context.packageName}".toUri())

        override fun matchesFilter(query: String): Boolean {
            return label.containsIgnoreAccents(query)
        }
    }

    private fun ShortcutRepository.Shortcut.toShortcutLaunchItem(label: String?): ShortcutLaunchItem {
        return ShortcutLaunchItem(
            label = label ?: this.label,
            drawable = drawable,
            isRenamed = label != null,
            shortcut = this,
        )
    }

    fun onContactsPermissionChanged() {
        contactRepository.onContactsPermissionChanged()
    }

    fun onRequestNotificationListenerPermissionClick() {
        settingsRepository.hasSeenRequestNotificationListenerPermissionBanner.value = true
        intentToStart.tryEmit(
            Pair(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                    .putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        ComponentName(getApplication(), NotificationListenerService::class.java).flattenToString(),
                    )
            } else {
                Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
            },
            null)
        )
    }

    val hasNotificationListenerPermissionSignal = signalStateFlow()
    val hasNotificationListenerPermission: Flow<Boolean> = hasNotificationListenerPermissionSignal.map {
        notificationRepository.hasNotificationListenerPermission()
    }.distinctUntilChanged()

    val hasSeenRequestNotificationListenerPermissionBanner: StateFlow<Boolean> =
        settingsRepository.hasSeenRequestNotificationListenerPermissionBanner

    val alignmentBottom: StateFlow<Boolean> = settingsRepository.alignmentBottom
    val alignmentRight: StateFlow<Boolean> = settingsRepository.alignmentRight
    val wallpaperOpacity: StateFlow<Float> = settingsRepository.wallpaperOpacity
    val showNotificationsButton: StateFlow<Boolean> = settingsRepository.showNotificationsButton
    val keyboardHack: StateFlow<Boolean> = settingsRepository.keyboardHack
}
