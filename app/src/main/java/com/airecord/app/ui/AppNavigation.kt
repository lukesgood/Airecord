package com.airecord.app.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.airecord.app.ui.detail.MeetingDetailScreen
import com.airecord.app.ui.list.MeetingListScreen
import com.airecord.app.ui.record.RecordScreen
import com.airecord.app.ui.settings.SettingsScreen

@Composable
fun AppNavigation() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "list") {
        composable("list") {
            MeetingListScreen(
                onOpenMeeting = { nav.navigate("detail/$it") },
                onOpenRecorder = { nav.navigate("record/$it") },
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable("record/{id}") { entry ->
            val id = entry.arguments?.getString("id").orEmpty()
            RecordScreen(
                meetingId = id,
                onBack = { nav.popBackStack() },
                onFinished = {
                    nav.navigate("detail/$id") { popUpTo("list") }
                },
            )
        }
        composable("detail/{id}") { entry ->
            MeetingDetailScreen(
                meetingId = entry.arguments?.getString("id").orEmpty(),
                onBack = { nav.popBackStack() },
                onOpenSettings = { nav.navigate("settings") },
            )
        }
        composable("settings") {
            SettingsScreen(onBack = { nav.popBackStack() })
        }
    }
}
