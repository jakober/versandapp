package de.versandapp.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import de.versandapp.ui.detail.ParcelDetailScreen
import de.versandapp.ui.list.ParcelListScreen
import de.versandapp.ui.mailimport.MailImportScreen
import de.versandapp.ui.mailimport.MailImportViewModel
import de.versandapp.ui.settings.SettingsScreen
import de.versandapp.ui.theme.VersandAppTheme

class MainActivity : ComponentActivity() {

    // ID des Pakets, das aus einer Benachrichtigung heraus geöffnet werden soll.
    private val pendingParcelId = mutableStateOf<Long?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingParcelId.value = readParcelId(intent)
        enableEdgeToEdge()
        setContent {
            VersandAppTheme {
                VersandAppNavHost(pendingParcelId)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        pendingParcelId.value = readParcelId(intent)
    }

    private fun readParcelId(intent: Intent?): Long? {
        val id = intent?.getLongExtra(EXTRA_PARCEL_ID, -1L) ?: -1L
        return id.takeIf { it > 0 }
    }

    companion object {
        const val EXTRA_PARCEL_ID = "parcelId"
    }
}

@Composable
private fun VersandAppNavHost(pendingParcelId: MutableState<Long?>) {
    val navController = rememberNavController()
    val viewModel: ParcelViewModel = viewModel(factory = ParcelViewModel.Factory)

    NotificationPermissionRequest()

    // Auf eine aus der Benachrichtigung übergebene Paket-ID reagieren (Deep-Link).
    LaunchedEffect(pendingParcelId.value) {
        val id = pendingParcelId.value
        if (id != null) {
            navController.navigate("detail/$id")
            pendingParcelId.value = null
        }
    }

    NavHost(navController = navController, startDestination = "list") {
        composable("list") {
            ParcelListScreen(
                viewModel = viewModel,
                onParcelClick = { id -> navController.navigate("detail/$id") },
                onImportClick = { navController.navigate("import") },
                onSettingsClick = { navController.navigate("settings") },
            )
        }
        composable("settings") {
            SettingsScreen(viewModel = viewModel, onBack = { navController.popBackStack() })
        }
        composable("import") {
            val importViewModel: MailImportViewModel = viewModel(factory = MailImportViewModel.Factory)
            MailImportScreen(
                viewModel = importViewModel,
                onBack = { navController.popBackStack() },
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

/** Fragt ab Android 13 einmalig die Berechtigung für Benachrichtigungen ab. */
@Composable
private fun NotificationPermissionRequest() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* Ablehnung ist ok – dann gibt es einfach keine Benachrichtigungen */ }

    LaunchedEffect(Unit) {
        val granted = ContextCompat.checkSelfPermission(
            context, Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
}
