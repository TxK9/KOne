package io.nekohasekai.sfa.compose.screen.profile

import android.net.Uri
import androidx.compose.animation.AnimatedContentTransitionScope
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

@Composable
fun EditProfileRoute(profileId: Long, onNavigateBack: () -> Unit, modifier: Modifier = Modifier) {
    if (profileId == -1L) {
        LaunchedEffect(Unit) {
            onNavigateBack()
        }
        return
    }

    val navController = rememberNavController()
    val sharedViewModel: EditProfileViewModel = viewModel()

    LaunchedEffect(profileId) {
        sharedViewModel.loadProfile(profileId)
    }

    NavHost(
        navController = navController,
        startDestination = "edit_profile",
        modifier = modifier,
    ) {
        composable(
            route = "edit_profile",
            enterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300),
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300),
                )
            },
            popEnterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300),
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300),
                )
            },
        ) {
            EditProfileScreen(
                profileId = profileId,
                onNavigateBack = onNavigateBack,
                onNavigateToIconSelection = { currentIconId ->
                    navController.navigate("icon_selection/${Uri.encode(currentIconId ?: "null")}") {
                        launchSingleTop = true
                    }
                },
                viewModel = sharedViewModel,
            )
        }

        composable(
            route = "icon_selection/{currentIconId}",
            arguments =
            listOf(
                navArgument("currentIconId") {
                    type = NavType.StringType
                    nullable = true
                },
            ),
            enterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300),
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300),
                )
            },
            popEnterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300),
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300),
                )
            },
        ) { backStackEntry ->
            val currentIconId =
                backStackEntry.arguments?.getString("currentIconId")
                    ?.takeIf { it != "null" }

            IconSelectionScreen(
                currentIconId = currentIconId,
                onIconSelected = { iconId ->
                    sharedViewModel.updateIcon(iconId)
                    navController.popBackStack("edit_profile", inclusive = false)
                },
                onNavigateBack = {
                    navController.popBackStack("edit_profile", inclusive = false)
                },
            )
        }

        composable(
            route = "edit_content/{isReadOnly}",
            arguments =
            listOf(
                navArgument("isReadOnly") {
                    type = NavType.BoolType
                    defaultValue = false
                },
            ),
            enterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300),
                )
            },
            exitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Left,
                    animationSpec = tween(300),
                )
            },
            popEnterTransition = {
                slideIntoContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300),
                )
            },
            popExitTransition = {
                slideOutOfContainer(
                    AnimatedContentTransitionScope.SlideDirection.Right,
                    animationSpec = tween(300),
                )
            },
        ) {
            // A restored back stack from an older install must not reopen raw configuration.
            LaunchedEffect(Unit) {
                navController.popBackStack("edit_profile", inclusive = false)
            }
        }
    }
}
