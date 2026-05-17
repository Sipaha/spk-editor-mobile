package ru.sipaha.spkremote.app.ui.nav

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import ru.sipaha.spkremote.app.ui.qr.QrPairingScreen
import ru.sipaha.spkremote.app.ui.settings.SettingsScreen
import ru.sipaha.spkremote.app.ui.solutions.SessionDetailScreen
import ru.sipaha.spkremote.app.ui.solutions.SolutionDetailScreen
import ru.sipaha.spkremote.app.ui.solutions.SolutionsListScreen
import ru.sipaha.spkremote.app.vm.ConnectionBanner
import ru.sipaha.spkremote.app.vm.MainViewModel
import ru.sipaha.spkremote.app.vm.UiState

/**
 * Routes:
 *   pairing                                              — QR + manual URL entry (R-5a/b).
 *   connecting                                           — handshake spinner.
 *   solutions                                            — list of solutions on the paired editor.
 *   solutions/{solutionId}                               — sessions in one solution.
 *   solutions/{solutionId}/sessions/{sessionId}          — chat surface (R-5d).
 *
 * The nav graph is the source of truth for navigation. Pairing success is
 * detected via [MainViewModel.state]: when it flips to [UiState.Connected]
 * we push `solutions` onto the back stack (popping any prior pairing entry).
 * When it flips back to Disconnected we pop everything except `pairing`.
 *
 * R-6a adds a persistent banner above the NavHost surfaced from
 * [MainViewModel.connectionBanner]. It's hidden in Connected/Disconnected
 * and surfaces a one-line "reconnecting…" or "re-pair required" state.
 */
@Composable
fun AppNav(viewModel: MainViewModel) {
    val navController = rememberNavController()
    val uiState by viewModel.state.collectAsState()
    val banner by viewModel.connectionBanner.collectAsState()
    val navStateRepository = viewModel.navStateRepository

    // R-6d: persist the resolved nav route on every back-stack change so
    // a cold-start can land the user back on the deepest screen they
    // were on. The route string includes substituted arguments (e.g.
    // `solutions/abc/sessions/xyz`) — we rebuild that from the entry's
    // NavArguments because Compose-Navigation only exposes the route
    // *template* on `currentDestination`. See [resolvedRoute] below.
    LaunchedEffect(navController) {
        navController.currentBackStackEntryFlow.collect { entry ->
            val resolved = resolvedRoute(entry) ?: return@collect
            navStateRepository.saveRoute(resolved)
        }
    }

    // One-shot deep-link restore after a successful pairing replay. The
    // route is restored at most once per VM instance — the `restored`
    // flag prevents a `popBackStack()` later in the session from re-
    // triggering navigation back to the saved deep route.
    var restored by remember { mutableStateOf(false) }

    LaunchedEffect(uiState) {
        when (uiState) {
            is UiState.Connected -> {
                val current = navController.currentDestination?.route
                if (current == null || current == "pairing" || current == "connecting") {
                    val savedRoute = if (!restored) navStateRepository.loadRoute() else null
                    val target = savedRoute?.takeIf { it.startsWith("solutions") } ?: "solutions"
                    restored = true
                    navController.navigate(target) {
                        popUpTo("pairing") { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }
            is UiState.Connecting -> {
                val current = navController.currentDestination?.route
                if (current != "connecting" && current != "solutions") {
                    navController.navigate("connecting") {
                        popUpTo("pairing") { inclusive = false }
                        launchSingleTop = true
                    }
                }
            }
            is UiState.Disconnected -> {
                navController.popBackStack(route = "pairing", inclusive = false)
            }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        ConnectionStateBanner(banner)
        NavHost(navController = navController, startDestination = "pairing") {
            composable("pairing") {
                val s = uiState
                val initialUrl = (s as? UiState.Disconnected)?.lastUrl.orEmpty()
                val error = (s as? UiState.Disconnected)?.error
                QrPairingScreen(
                    initialUrl = initialUrl,
                    error = error,
                    onPair = viewModel::connect,
                )
            }
            composable("connecting") {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center,
                ) { CircularProgressIndicator() }
            }
            composable("solutions") {
                SolutionsListScreen(
                    viewModel = viewModel,
                    onOpenSolution = { sol ->
                        navController.navigate("solutions/${sol.id}")
                    },
                    onOpenSettings = { navController.navigate("settings") },
                )
            }
            composable("settings") {
                SettingsScreen(
                    viewModel = viewModel,
                    onBack = { navController.popBackStack() },
                    // Forget Server: clear persistence + connection, then
                    // pop everything back to `pairing`. We don't rely on
                    // the UiState-watching LaunchedEffect because the user
                    // may have arrived at Settings via a freshly persisted
                    // connection that already pushed `solutions` onto the
                    // back stack.
                    onForget = {
                        viewModel.forgetPairing()
                        navController.navigate("pairing") {
                            popUpTo("pairing") { inclusive = true }
                            launchSingleTop = true
                        }
                    },
                )
            }
            composable(
                route = "solutions/{solutionId}",
                arguments = listOf(navArgument("solutionId") { type = NavType.StringType }),
            ) { entry ->
                val solutionId = entry.arguments?.getString("solutionId").orEmpty()
                SolutionDetailScreen(
                    viewModel = viewModel,
                    solutionId = solutionId,
                    onOpenSession = { session ->
                        navController.navigate("solutions/$solutionId/sessions/${session.id}")
                    },
                    onOpenSessionById = { sessionId ->
                        navController.navigate("solutions/$solutionId/sessions/$sessionId")
                    },
                    onBack = { navController.popBackStack() },
                )
            }
            composable(
                route = "solutions/{solutionId}/sessions/{sessionId}",
                arguments = listOf(
                    navArgument("solutionId") { type = NavType.StringType },
                    navArgument("sessionId") { type = NavType.StringType },
                ),
            ) { entry ->
                val sessionId = entry.arguments?.getString("sessionId").orEmpty()
                SessionDetailScreen(
                    viewModel = viewModel,
                    sessionId = sessionId,
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

/**
 * Build the *resolved* route string for [entry] — substituting any
 * `{argName}` placeholders in the destination route template with the
 * actual argument values from the back-stack entry.
 *
 * Compose-Navigation only exposes the template on `destination.route`,
 * not the resolved form (e.g. it gives us `solutions/{solutionId}` even
 * though the user is on `solutions/abc`). This helper closes that gap
 * so [NavStateRepository.saveRoute] can persist a route that
 * `navController.navigate(...)` will accept verbatim on the next cold
 * start.
 *
 * Returns `null` when the entry has no route (synthetic back-stack
 * entries, e.g. the root graph node).
 */
private fun resolvedRoute(entry: NavBackStackEntry): String? {
    val template = entry.destination.route ?: return null
    val args = entry.arguments ?: return template
    return Regex("""\{([^}]+)\}""").replace(template) { match ->
        val key = match.groupValues[1]
        args.getString(key) ?: match.value
    }
}

/**
 * Surfaced strip above the nav stack when the underlying socket is in
 * trouble. We deliberately do NOT block input on the screen below — the
 * user can still navigate, read existing transcripts, and even queue new
 * messages (which [RemoteClient.queueCall] will flush on reconnect).
 *
 * Color choice:
 *   - [ConnectionBanner.Reconnecting] → `tertiaryContainer` (yellow-ish,
 *     "transient, will heal itself").
 *   - [ConnectionBanner.FailedTerminal] → `errorContainer` (red, "user
 *     action required — re-pair").
 */
@Composable
private fun ConnectionStateBanner(banner: ConnectionBanner) {
    val (background, text) = when (banner) {
        ConnectionBanner.Hidden -> return
        is ConnectionBanner.Reconnecting -> {
            val seconds = (banner.nextRetryMs / 1000).coerceAtLeast(1)
            MaterialTheme.colorScheme.tertiaryContainer to
                "Reconnecting (attempt ${banner.attempt}, next try in ${seconds}s)…"
        }
        is ConnectionBanner.FailedTerminal -> {
            MaterialTheme.colorScheme.errorContainer to
                "Connection failed (${banner.reason}). Re-pair required."
        }
    }
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = background,
    ) {
        Text(
            text = text,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}
