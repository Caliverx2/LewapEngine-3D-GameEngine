/*
package com.krzychu.PauseButtonMod

import org.lewapnoob.gridMap.*

class PauseButtonMod : LewapMod {
    private lateinit var game: gridMap
    override fun getName() = "Pause Button Mod"


    override fun onEnable(game: gridMap) {
        println("[PauseButtonMod] loaded successfully!")
        this.game = game
    }

    override fun onUIInit(uiManager: UIManager) {
        val pauseMenu = uiManager.getPanel(GameState.PAUSED)
        pauseMenu.add(UIButton(game.baseCols*2 - 200, 100, 400, 50, "Custom Button", textAlign = TextAlign.RIGHT, padding = 1, tooltip = "This is a custom button!") {
            println("The mod button was clicked!")
        })
    }
}
*/