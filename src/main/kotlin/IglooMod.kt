/*
package org.lewapnoob.gridMap

import java.awt.Color

class IglooMod : LewapMod {
    override fun getName() = "Instant Igloo Mod"

    override fun onEnable(game: gridMap) {
        println("IglooMod załadowany! Postaw blok #00FF0F (Limonkowy), aby zbudować Igloo.")
        // Dodajemy ten magiczny blok do ekwipunku gracza na start
        game.addItem("#00FF0F", 10)
    }

    override fun onBlockPlace(game: gridMap, x: Int, y: Int, z: Int, color: Int): Boolean {
        // Sprawdzamy kolor: #00FF0F (R=0, G=255, B=15)
        // Uwaga: Color.decode("#00FF0F").rgb może zwrócić wartość z Alpha=255
        val magicColor = Color(0, 255, 15).rgb

        // Porównujemy kolory (ignorując kanał alfa dla pewności, lub używając pełnego inta)
        if ((color and 0xFFFFFF) == (magicColor and 0xFFFFFF)) {
            println("Wykryto magiczny blok! Budowanie Igloo na $x, $y, $z")

            // Używamy modelu z gridMapStructures.kt
            // IglooModelData jest dostępny globalnie w Twoim projekcie
            game.placeStructure(IglooModelData, x, y, z)

            return true // Zwracamy true -> "My to obsłużyliśmy, nie stawiaj zwykłego bloku"
        }

        return false // To nie nasz blok, niech gra robi swoje
    }
}
*/