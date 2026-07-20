package de.versandapp.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.versandapp.ui.detail.ParcelDetailScreen
import de.versandapp.ui.list.ParcelListScreen
import de.versandapp.ui.theme.VersandAppTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VersandAppTheme {
                VersandAppNavHost()
            }
        }
    }
}

@Composable
private fun VersandAppNavHost() {
    val navController = rememberNavController()
    val viewModel: ParcelViewModel = viewModel(factory = ParcelViewModel.Factory)

    NavHost(navController = navController, startDestination = "list") {
        composable("list") {
            ParcelListScreen(
                viewModel = viewModel,
                onParcelClick = { id -> navController.navigate("detail/$id") },
            )
        }
        composable(
            route = "detail/{parcelId}",
            arguments = listOf(navArgument("parcelId") { type = NavType.LongType }),
        ) { backStackEntry ->
            val parcelId = backStackEntry.arguments?.getLong("parcelId") ?: return@composable
            ParcelDetailScreen(
                viewModel = viewModel,
                parcelId = parcelId,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
