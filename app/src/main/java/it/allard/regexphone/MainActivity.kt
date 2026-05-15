package it.allard.regexphone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import it.allard.regexphone.data.RuleRepository
import it.allard.regexphone.ui.EditRuleScreen
import it.allard.regexphone.ui.RegexPhoneTheme
import it.allard.regexphone.ui.RulesListScreen

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        RuleRepository.init(applicationContext)
        enableEdgeToEdge()
        setContent {
            RegexPhoneTheme {
                AppNav()
            }
        }
    }
}

@Composable
private fun AppNav() {
    val nav = rememberNavController()
    NavHost(navController = nav, startDestination = "rules") {
        composable("rules") {
            RulesListScreen(
                onAddRule = { nav.navigate("edit?id=-1") },
                onEditRule = { id -> nav.navigate("edit?id=$id") },
            )
        }
        composable(
            route = "edit?id={id}",
            arguments = listOf(navArgument("id") {
                type = NavType.LongType
                defaultValue = -1L
            }),
        ) { backStackEntry ->
            val id = backStackEntry.arguments?.getLong("id") ?: -1L
            EditRuleScreen(
                ruleId = id.takeIf { it >= 0 },
                onDone = { nav.popBackStack() },
            )
        }
    }
}
