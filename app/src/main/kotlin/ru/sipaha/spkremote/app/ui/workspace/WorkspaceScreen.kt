package ru.sipaha.spkremote.app.ui.workspace

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import ru.sipaha.spkremote.app.vm.MainViewModel
import ru.sipaha.spkremote.app.vm.OpenSessionVM
import ru.sipaha.spkremote.app.vm.OpenSolutionVM
import ru.sipaha.spkremote.app.vm.WorkspaceUiState

@OptIn(ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun WorkspaceScreen(
    viewModel: MainViewModel,
    onOpenSession: (sessionId: String) -> Unit,
    onOpenProjects: (solutionId: String) -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPicker: () -> Unit,
    onCreateNewSolution: () -> Unit,
    onCreateNewSessionFor: (solutionId: String) -> Unit,
) {
    val state by viewModel.workspaceState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Workspace") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(Icons.Filled.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
        floatingActionButton = {
            ExpandableFab(
                onCreateNew = onCreateNewSolution,
                onOpenPicker = onOpenPicker,
            )
        },
    ) { padding ->
        Box(Modifier.padding(padding)) {
            when (val s = state) {
                WorkspaceUiState.Loading -> LoadingState()
                is WorkspaceUiState.Error -> ErrorState(s.message)
                is WorkspaceUiState.Loaded -> {
                    if (s.snapshot.solutions.isEmpty()) {
                        EmptyState(onOpenPicker = onOpenPicker, onCreateNew = onCreateNewSolution)
                    } else {
                        if (s.stale) StaleProgressBar()
                        WorkspaceList(
                            solutions = s.snapshot.solutions,
                            onOpenSession = onOpenSession,
                            onOpenProjects = onOpenProjects,
                            onCloseSolution = { id -> viewModel.closeSolution(id) },
                            onDeleteSolution = { id -> viewModel.deleteSolution(id) },
                            onCloseSession = { id -> viewModel.closeSessionTab(id) },
                            onDeleteSession = { id -> viewModel.deleteSession(id) },
                            onCreateNewSessionFor = onCreateNewSessionFor,
                        )
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceList(
    solutions: List<OpenSolutionVM>,
    onOpenSession: (String) -> Unit,
    onOpenProjects: (String) -> Unit,
    onCloseSolution: (String) -> Unit,
    onDeleteSolution: (String) -> Unit,
    onCloseSession: (String) -> Unit,
    onDeleteSession: (String) -> Unit,
    onCreateNewSessionFor: (String) -> Unit,
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        solutions.forEach { sol ->
            stickyHeader(key = "header-${sol.id}") {
                SolutionHeader(
                    sol = sol,
                    onOpenProjects = { onOpenProjects(sol.id) },
                    onCloseSolution = { onCloseSolution(sol.id) },
                    onDeleteSolution = { onDeleteSolution(sol.id) },
                )
            }
            items(sol.sessions, key = { it.id }) { session ->
                SessionRow(
                    session = session,
                    onClick = { onOpenSession(session.id) },
                    onCloseConsole = { onCloseSession(session.id) },
                    onDeleteSession = { onDeleteSession(session.id) },
                )
            }
            item(key = "new-${sol.id}") {
                NewConsoleRow(onClick = { onCreateNewSessionFor(sol.id) })
            }
            item { HorizontalDivider() }
        }
    }
}

// (SolutionHeader, SessionRow, NewConsoleRow, ExpandableFab, EmptyState, ErrorState,
//  LoadingState, StaleProgressBar — implemented in Task D2.)

@Composable
private fun SolutionHeader(
    sol: OpenSolutionVM,
    onOpenProjects: () -> Unit,
    onCloseSolution: () -> Unit,
    onDeleteSolution: () -> Unit,
) {
    TODO("D2")
}

@Composable
private fun SessionRow(
    session: OpenSessionVM,
    onClick: () -> Unit,
    onCloseConsole: () -> Unit,
    onDeleteSession: () -> Unit,
) {
    TODO("D2")
}

@Composable
private fun NewConsoleRow(
    onClick: () -> Unit,
) {
    TODO("D2")
}

@Composable
private fun ExpandableFab(
    onCreateNew: () -> Unit,
    onOpenPicker: () -> Unit,
) {
    TODO("D2")
}

@Composable
private fun EmptyState(
    onOpenPicker: () -> Unit,
    onCreateNew: () -> Unit,
) {
    TODO("D2")
}

@Composable
private fun ErrorState(
    message: String,
) {
    TODO("D2")
}

@Composable
private fun LoadingState() {
    TODO("D2")
}

@Composable
private fun StaleProgressBar() {
    TODO("D2")
}
