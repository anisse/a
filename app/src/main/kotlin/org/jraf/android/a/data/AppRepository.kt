/*
 * This source is part of the
 *      _____  ___   ____
 *  __ / / _ \/ _ | / __/___  _______ _
 * / // / , _/ __ |/ _/_/ _ \/ __/ _ `/
 * \___/_/|_/_/ |_/_/ (_)___/_/  \_, /
 *                              /___/
 * repository.
 *
 * Copyright (C) 2023-present Benoit 'BoD' Lubek (BoD@JRAF.org)
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
package org.jraf.android.a.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.graphics.drawable.Drawable
import android.os.Process;
import android.os.UserHandle
import android.util.DisplayMetrics
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import org.jraf.android.a.BuildConfig
import org.jraf.android.a.R
import org.jraf.android.a.util.Key
import org.jraf.android.a.util.invoke
import org.jraf.android.a.util.signalStateFlow

class AppRepository(context: Context) {
    companion object : Key<AppRepository>

    private val launcherApps: LauncherApps = context.getSystemService(LauncherApps::class.java)

    private val onPackagesChanged = signalStateFlow()

    init {
        launcherApps.registerCallback(
            object : LauncherApps.Callback() {
                override fun onPackageRemoved(packageName: String?, user: UserHandle?) {
                    onPackagesChanged()
                }

                override fun onPackageAdded(packageName: String?, user: UserHandle?) {
                    onPackagesChanged()
                }

                override fun onPackageChanged(packageName: String?, user: UserHandle?) {
                    onPackagesChanged()
                }

                override fun onPackagesAvailable(
                    packageNames: Array<out String>?,
                    user: UserHandle?,
                    replacing: Boolean
                ) {
                    onPackagesChanged()
                }

                override fun onPackagesUnavailable(
                    packageNames: Array<out String>?,
                    user: UserHandle?,
                    replacing: Boolean
                ) {
                    onPackagesChanged()
                }
            })
    }

    data class App(
        val label: String,
        val drawable: Drawable,
        val componentName: ComponentName,
        val user: UserHandle?,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (javaClass != other?.javaClass) return false

            other as App

            if (label != other.label) return false
            if (drawable::class.java != other.drawable::class.java) return false
            if (componentName != other.componentName) return false
            if (user != other.user) return false

            return true
        }

        override fun hashCode(): Int {
            var result = label.hashCode()
            result = 31 * result + drawable::class.java.hashCode()
            result = 31 * result + componentName::class.java.hashCode()
            if (user != null) {
                result = 31 * result + user::class.java.hashCode()
            }
            return result
        }
    }

    private var firstLoad = true

    @OptIn(ExperimentalCoroutinesApi::class)
    val allApps: Flow<List<App>> = onPackagesChanged.flatMapLatest {
        flow {
            // On the first load, we first emit the apps without their icons to get something as fast as possible
            val launcherActivityInfos: List<LauncherActivityInfo> = launcherApps.profiles.flatMap { profile ->
                launcherApps.getActivityList(null, profile)
                .filter { launcherActivityInfo ->
                    // Don't show ourselves, unless we're in debug mode
                    BuildConfig.DEBUG || launcherActivityInfo.applicationInfo.packageName != context.packageName
                }
            }
            if (firstLoad) {
                firstLoad = false
                val pendingDrawable = ContextCompat.getDrawable(context, R.drawable.pending)!!
                emit(
                    launcherActivityInfos.map { launcherActivityInfo ->
                        var user = launcherActivityInfo.getUser()
                        if (user.equals(Process.myUserHandle())) {
                            user = null
                        }
                        App(
                            label = launcherActivityInfo.label.toString(),
                            drawable = pendingDrawable,
                            componentName = launcherActivityInfo.getComponentName(),
                            user = user,
                        )
                    }
                )
            }

            emit(
                launcherActivityInfos.map { launcherActivityInfo ->
                    var user = launcherActivityInfo.getUser()
                    if (user.equals(Process.myUserHandle())) {
                        user = null
                    }
                    App(
                        label = launcherActivityInfo.label.toString(),
                        drawable = launcherActivityInfo.getIcon(DisplayMetrics.DENSITY_XHIGH),
                        componentName = launcherActivityInfo.getComponentName(),
                        user = user,
                    )
                }
            )
        }
    }
        .distinctUntilChanged()
        .flowOn(Dispatchers.IO)
}
