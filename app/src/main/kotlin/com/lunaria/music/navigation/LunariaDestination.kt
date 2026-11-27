package com.lunaria.music.navigation

import androidx.annotation.DrawableRes
import com.lunaria.core.designsystem.icon.LunariaIcons
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
    Home(HomeRoute, HomeRoute::class, "Home", LunariaIcons.HomeOutlined, LunariaIcons.HomeFilled),
    Library(LibraryRoute, LibraryRoute::class, "Library", LunariaIcons.LibraryOutlined, LunariaIcons.LibraryFilled),
    Search(SearchRoute, SearchRoute::class, "Search", LunariaIcons.Search, LunariaIcons.Search),
    Settings(SettingsRoute, SettingsRoute::class, "Settings", LunariaIcons.SettingsOutlined, LunariaIcons.SettingsFilled),
}
