package com.example.lume

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

enum class Screen {
    Library,
    Player
}

class CustomNavigator {
    var currentScreen by mutableStateOf(Screen.Library)
        private set
    private val backStack = mutableListOf<Screen>()

    fun pushFade(screen: Screen) {
        backStack.add(currentScreen)
        currentScreen = screen
    }

    fun pushReplacementFade(screen: Screen) {
        currentScreen = screen
    }

    fun pop(): Boolean {
        if (backStack.isNotEmpty()) {
            currentScreen = backStack.removeAt(backStack.size - 1)
            return true
        }
        return false
    }
}
