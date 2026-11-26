package com.lumyn.music.navigation

import androidx.annotation.DrawableRes
import com.lumyn.core.designsystem.icon.LumynIcons
import kotlin.reflect.KClass
import kotlinx.serialization.Serializable

@Serializable
data object HomeRoute

@Serializable
data object LibraryRoute

@Serializable
data object SearchRoute

@Serializable
data object SettingsRoute

@Serializable
data class LibraryDetailRoute(
    val kind: String,
    val key: String,
)

@Serializable
data object ListeningStatsRoute

/** The four top-level destinations, always in this order on every screen size. */
enum class TopLevelDestination(
    val route: Any,
    val routeClass: KClass<*>,
    val label: String,
    @DrawableRes val icon: Int,
    @DrawableRes val selectedIcon: Int,
) {
    Home(HomeRoute, HomeRoute::class, "Home", LumynIcons.HomeOutlined, LumynIcons.HomeFilled),
    Library(LibraryRoute, LibraryRoute::class, "Library", LumynIcons.LibraryOutlined, LumynIcons.LibraryFilled),
    Search(SearchRoute, SearchRoute::class, "Search", LumynIcons.Search, LumynIcons.Search),
    Settings(SettingsRoute, SettingsRoute::class, "Settings", LumynIcons.SettingsOutlined, LumynIcons.SettingsFilled),
}
