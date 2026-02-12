package org.lewapnoob.gridMap

import java.awt.Graphics2D
import java.io.File
import java.net.URLClassLoader
import java.util.ServiceLoader

/**
 * Interfejs moda.
 */
interface LewapMod {
    fun getName(): String
    fun onEnable(game: gridMap)
    
    /**
     * Wywoływane, gdy gracz próbuje postawić blok.
     * @return true, jeśli mod obsłużył akcję i silnik ma NIE stawiać oryginalnego bloku.
     */
    fun onBlockPlace(game: gridMap, x: Int, y: Int, z: Int, color: Int): Boolean {
        return false
    }

    /**
     * Wywoływane w każdej klatce renderowania 2D (GUI).
     * Pozwala modom rysować własne napisy, paski życia itp.
     * @deprecated Użyj onUIInit i dodaj komponenty do UIManager
     */
    fun onRender(g: Graphics2D, width: Int, height: Int) {}

    /**
     * Wywoływane przy inicjalizacji interfejsu użytkownika.
     * Tutaj mody powinny dodawać swoje przyciski i panele do UIManager.
     */
    fun onUIInit(uiManager: UIManager) {}

    /**
     * Wywoływane, gdy gracz naciśnie klawisz.
     * @return true, jeśli mod "zjadł" klawisz (gra ma go zignorować).
     */
    fun onKeyPress(keyCode: Int): Boolean {
        return false
    }

    /**
     * Wywoływane w każdym cyklu logicznym gry (30 razy na sekundę).
     * Tutaj wrzucamy logikę ciągłą: latanie, regenerację życia, zmiany fizyki.
     */
    fun onTick(game: gridMap) {}

    /**
     * Wywoływane w każdej klatce renderowania, niezależnie od stanu gry (MENU, PAUSE, IN_GAME).
     * Służy do aktualizacji logiki UI, animacji interfejsu itp.
     */
    fun onTickUI(game: gridMap) {}

    /**
     * Wywoływane po wyrenderowaniu świata, ale przed UI.
     * Pozwala modom rysować własne obiekty 3D (np. linie, boxy).
     */
    fun onRender3D(game: gridMap) {}
}

/**
 * Zarządca modów.
 */
class ModLoader(private val game: gridMap, rootDir: File) {
    private val mods = mutableListOf<LewapMod>()
    private val modsDir = File(rootDir, "mods")

    fun loadMods() {
        if (!modsDir.exists()) {
            modsDir.mkdirs()
            println("ModLoader: Utworzono folder modów: ${modsDir.absolutePath}")
            return
        }

        val jarFiles = modsDir.listFiles { _, name -> name.endsWith(".jar") } ?: return

        if (jarFiles.isEmpty()) {
            println("ModLoader: Brak plików .jar w folderze modów.")
            return
        }

        val urls = jarFiles.map { it.toURI().toURL() }.toTypedArray()
        // Ważne: parent = this.javaClass.classLoader pozwala modom widzieć klasy silnika
        val classLoader = URLClassLoader(urls, this.javaClass.classLoader)
        val serviceLoader = ServiceLoader.load(LewapMod::class.java, classLoader)

        println("ModLoader: Skanowanie modów...")
        for (mod in serviceLoader) {
            try {
                println("ModLoader: Znaleziono moda -> ${mod.getName()}")
                registerMod(mod)
            } catch (e: Exception) {
                println("BŁĄD: Nie udało się załadować moda ${mod.getName()}: ${e.message}")
                e.printStackTrace()
            }
        }
        println("ModLoader: Załadowano ${mods.size} modów.")
    }
    
    fun registerMod(mod: LewapMod) {
        mods.add(mod)
        mod.onEnable(game)
        mod.onUIInit(game.uiManager)
    }

    fun notifyBlockPlace(x: Int, y: Int, z: Int, color: Int): Boolean {
        // Jeśli którykolwiek mod zwróci true, przerywamy stawianie zwykłego bloku
        return mods.any { it.onBlockPlace(game, x, y, z, color) }
    }

    fun notifyRender(g: Graphics2D, width: Int, height: Int) {
        mods.forEach { it.onRender(g, width, height) }
    }

    fun notifyKeyPress(keyCode: Int): Boolean {
        return mods.any { it.onKeyPress(keyCode) }
    }

    fun notifyTick() {
        mods.forEach { it.onTick(game) }
    }

    fun notifyTickUI() {
        mods.forEach { it.onTickUI(game) }
    }

    fun notifyRender3D() {
        mods.forEach { it.onRender3D(game) }
    }
}