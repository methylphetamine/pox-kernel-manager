package com.pox.kernelmanager

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.pox.kernelmanager.ui.screens.DashboardScreen
import com.pox.kernelmanager.ui.screens.DiagnosticsScreen
import com.pox.kernelmanager.ui.screens.FeaturesScreen
import com.pox.kernelmanager.ui.screens.FlashScreen
import com.pox.kernelmanager.ui.screens.SettingsScreen
import com.pox.kernelmanager.ui.screens.UpdaterScreen
import com.pox.kernelmanager.ui.theme.PoxTheme
import com.pox.kernelmanager.ui.viewmodel.DashboardViewModel
import com.pox.kernelmanager.ui.viewmodel.DiagnosticsViewModel
import com.pox.kernelmanager.ui.viewmodel.FeaturesViewModel
import com.pox.kernelmanager.ui.viewmodel.FlashViewModel
import com.pox.kernelmanager.ui.viewmodel.UpdaterViewModel

data class NavItem(
    val title: String,
    val icon: ImageVector
)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            PoxTheme {
                MainContent()
            }
        }
    }
}

@Composable
fun MainContent() {
    val navItems = listOf(
        NavItem(title = "Dashboard", icon = Icons.Default.Speed),
        NavItem(title = "Tuning", icon = Icons.Default.Tune),
        NavItem(title = "Updater", icon = Icons.Default.Download),
        NavItem(title = "Flash", icon = Icons.Default.Bolt),
        NavItem(title = "Diagnostics", icon = Icons.Default.BugReport),
        NavItem(title = "Settings", icon = Icons.Default.Settings)
    )

    var selectedIndex by rememberSaveable { mutableIntStateOf(0) }

    val dashboardViewModel: DashboardViewModel = viewModel()
    val featuresViewModel: FeaturesViewModel = viewModel()
    val updaterViewModel: UpdaterViewModel = viewModel()
    val flashViewModel: FlashViewModel = viewModel()
    val diagnosticsViewModel: DiagnosticsViewModel = viewModel()

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                navItems.forEachIndexed { index, item ->
                    NavigationBarItem(
                        selected = selectedIndex == index,
                        onClick = { selectedIndex = index },
                        icon = { Icon(imageVector = item.icon, contentDescription = item.title) },
                        label = { Text(item.title) }
                    )
                }
            }
        }
    ) { innerPadding ->
        Crossfade(
            targetState = selectedIndex,
            label = "ScreenCrossfade",
            modifier = Modifier.padding(bottom = innerPadding.calculateBottomPadding())
        ) { index ->
            when (index) {
                0 -> DashboardScreen(viewModel = dashboardViewModel)
                1 -> FeaturesScreen(viewModel = featuresViewModel)
                2 -> UpdaterScreen(viewModel = updaterViewModel)
                3 -> FlashScreen(viewModel = flashViewModel)
                4 -> DiagnosticsScreen(viewModel = diagnosticsViewModel)
                5 -> SettingsScreen()
            }
        }
    }
}
