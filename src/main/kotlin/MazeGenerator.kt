package org.lewapnoob.MazeGeneratorDijkstra

import java.awt.*
import java.awt.event.ActionEvent
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.util.*
import javax.swing.*
import javax.swing.Timer

data class Node(val r: Int, val c: Int, var dist: Int = Int.MAX_VALUE, var prev: Node? = null) : Comparable<Node> {
    override fun compareTo(other: Node): Int {
        return dist.compareTo(other.dist)
    }
}

class MazePathfindingPanel : JPanel() {
    private val baseCols = 25
    private val baseRows = 17
    private val cellSize = 20

    private val gridMap = Array(baseRows * 2 + 1) { Array(baseCols * 2 + 1) { true } }

    private val visitedCells = Array(baseRows) { Array(baseCols) { false } }
    private val stack = Stack<Pair<Int, Int>>()
    private var currentLogicalCell: Pair<Int, Int>? = null
    private var generating = false

    private var path: List<Pair<Int, Int>>? = null
    private var agentPositionIndex = 0
    private var agentPathTimer: Timer? = null
    private var showAgent = false
    
    private var dijkstraTimer: Timer? = null
    private val dijkstraVisited = mutableSetOf<Pair<Int, Int>>()
    private var dijkstraPQ = PriorityQueue<Node>()
    private var dijkstraNodes: Array<Array<Node>>? = null
    private var dijkstraEnd: Pair<Int, Int>? = null
    private var searching = false

    init {
        preferredSize = Dimension((baseCols * 2 + 1) * cellSize, (baseRows * 2 + 1) * cellSize)
        setBackground(Color.DARK_GRAY)
        addKeyListener(KeyboardListener())
        isFocusable = true
        startGeneration()
    }

    private fun startGeneration() {
        for (r in gridMap.indices) {
            for (c in gridMap[0].indices) {
                gridMap[r][c] = true
            }
        }
        for (r in 0 until baseRows) {
            for (c in 0 until baseCols) {
                visitedCells[r][c] = false
            }
        }
        stack.clear()
        path = null
        agentPositionIndex = 0
        agentPathTimer?.stop()
        dijkstraTimer?.stop()
        showAgent = false
        searching = false
        dijkstraVisited.clear()

        currentLogicalCell = Pair(0, 0)
        visitedCells[0][0] = true
        gridMap[1][1] = false
        stack.push(currentLogicalCell)
        generating = true

        val mazeGenerationTimer = Timer(10) { e: ActionEvent ->
            if (generating) {
                generateStep()
                repaint()
            } else {
                (e.source as Timer).stop()
                startPathfinding()
            }
        }
        mazeGenerationTimer.start()
    }

    private fun generateStep() {
        if (stack.isNotEmpty()) {
            currentLogicalCell = stack.peek()
            val (r, c) = currentLogicalCell!!

            val unvisitedNeighbors = getUnvisitedNeighbors(r, c)

            if (unvisitedNeighbors.isNotEmpty()) {
                val nextCell = unvisitedNeighbors.random()
                val (nextR, nextC) = nextCell

                visitedCells[nextR][nextC] = true
                stack.push(nextCell)

                removeWallInGridMap(r, c, nextR, nextC)

                gridMap[nextR * 2 + 1][nextC * 2 + 1] = false

                currentLogicalCell = nextCell
            } else {
                stack.pop()
            }
        } else {
            generating = false
            addLoops()
        }
    }

    private fun getUnvisitedNeighbors(row: Int, col: Int): List<Pair<Int, Int>> {
        val neighbors = mutableListOf<Pair<Int, Int>>()
        if (row > 0 && !visitedCells[row - 1][col]) {
            neighbors.add(Pair(row - 1, col))
        }
        if (col < baseCols - 1 && !visitedCells[row][col + 1]) {
            neighbors.add(Pair(row, col + 1))
        }
        if (row < baseRows - 1 && !visitedCells[row + 1][col]) {
            neighbors.add(Pair(row + 1, col))
        }
        if (col > 0 && !visitedCells[row][col - 1]) {
            neighbors.add(Pair(row, col - 1))
        }

        return neighbors
    }

    private fun getPotentialLoopNeighbors(row: Int, col: Int): List<Pair<Int, Int>> {
        val neighbors = mutableListOf<Pair<Int, Int>>()
        if (row > 0) neighbors.add(Pair(row - 1, col))
        if (col < baseCols - 1) neighbors.add(Pair(row, col + 1))
        if (row < baseRows - 1) neighbors.add(Pair(row + 1, col))
        if (col > 0) neighbors.add(Pair(row, col - 1))
        return neighbors
    }

    private fun addLoops() {
        val loopsToAdd = (baseRows * baseCols) / 8

        repeat(loopsToAdd) {
            val r = (0 until baseRows).random()
            val c = (0 until baseCols).random()

            val neighbors = getPotentialLoopNeighbors(r, c)
            if (neighbors.isNotEmpty()) {
                val (nextR, nextC) = neighbors.random()
                removeWallInGridMap(r, c, nextR, nextC)
            }
        }
    }

    private fun removeWallInGridMap(r1: Int, c1: Int, r2: Int, c2: Int) {
        val gridR1 = r1 * 2 + 1
        val gridC1 = c1 * 2 + 1
        val gridR2 = r2 * 2 + 1
        val gridC2 = c2 * 2 + 1

        val wallGridR = (gridR1 + gridR2) / 2
        val wallGridC = (gridC1 + gridC2) / 2

        gridMap[wallGridR][wallGridC] = false
    }

    private fun startPathfinding() {
        val startLogical = Pair(0, 0)
        val endLogical = Pair(baseRows - 1, baseCols - 1)

        val startGrid = Pair(startLogical.first * 2 + 1, startLogical.second * 2 + 1)
        val endGrid = Pair(endLogical.first * 2 + 1, endLogical.second * 2 + 1)

        path = null
        agentPositionIndex = 0
        showAgent = false
        agentPathTimer?.stop()

        dijkstraEnd = endGrid

        dijkstraNodes = Array(gridMap.size) { r ->
            Array(gridMap[0].size) { c ->
                Node(r, c)
            }
        }

        dijkstraPQ.clear()
        dijkstraVisited.clear()

        val startNode = dijkstraNodes!![startGrid.first][startGrid.second]
        startNode.dist = 0
        dijkstraPQ.add(startNode)

        searching = true

        dijkstraTimer = Timer(10) {
            dijkstraStep()
            repaint()
        }
        dijkstraTimer?.start()
    }

    private fun dijkstraStep() {
        if (dijkstraPQ.isEmpty()) {
            searching = false
            dijkstraTimer?.stop()
            return
        }

        val u = dijkstraPQ.poll()
        dijkstraVisited.add(Pair(u.r, u.c))

        if (u.r == dijkstraEnd!!.first && u.c == dijkstraEnd!!.second) {
            searching = false
            dijkstraTimer?.stop()
            reconstructPath(u)
            startAgentAnimation()
            return
        }

        val dr = intArrayOf(-1, 1, 0, 0)
        val dc = intArrayOf(0, 0, -1, 1)

        for (i in 0 until 4) {
            val newR = u.r + dr[i]
            val newC = u.c + dc[i]

            if (newR >= 0 && newR < gridMap.size && newC >= 0 && newC < gridMap[0].size && !gridMap[newR][newC]) {
                val v = dijkstraNodes!![newR][newC]
                val newDist = u.dist + 1

                if (newDist < v.dist) {
                    dijkstraPQ.remove(v)
                    v.dist = newDist
                    v.prev = u
                    dijkstraPQ.add(v)
                }
            }
        }
    }

    private fun startAgentAnimation() {
        agentPositionIndex = 0
        showAgent = true

        agentPathTimer = Timer(100) {
            if (path != null && agentPositionIndex < path!!.size) {
                agentPositionIndex++
                repaint()
            } else {
                agentPathTimer?.stop()
                showAgent = false
                repaint()
            }
        }
        agentPathTimer?.start()
    }

    private fun reconstructPath(endNode: Node) {
        val shortestPath = mutableListOf<Pair<Int, Int>>()
        var curr: Node? = endNode
        while (curr != null) {
            shortestPath.add(0, Pair(curr.r, curr.c))
            curr = curr.prev
        }
        path = shortestPath
    }

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2d = g as Graphics2D

        for (r in gridMap.indices) {
            for (c in gridMap[0].indices) {
                val x = c * cellSize
                val y = r * cellSize

                if (gridMap[r][c]) {
                    g2d.color = Color.DARK_GRAY
                    g2d.fillRect(x, y, cellSize, cellSize)
                } else {
                    g2d.color = Color.lightGray
                    g2d.fillRect(x, y, cellSize, cellSize)
                }
            }
        }

        for ((r, c) in dijkstraVisited) {
            g2d.color = Color(100, 149, 237)
            g2d.fillRect(c * cellSize, r * cellSize, cellSize, cellSize)
        }

        path?.let {
            for ((r, c) in it) {
                g2d.color = Color(100, 70, 255, 150)
                g2d.fillRect(c * cellSize, r * cellSize, cellSize, cellSize)
            }

            for (i in 0 until agentPositionIndex.coerceAtMost(it.size)) {
                val (r, c) = it[i]
                g2d.color = Color(0, 150, 0, 150)
                g2d.fillRect(c * cellSize, r * cellSize, cellSize, cellSize)
            }
        }

        if (generating && currentLogicalCell != null) {
            val (r, c) = currentLogicalCell!!
            val gridR = r * 2 + 1
            val gridC = c * 2 + 1
            g2d.color = Color.CYAN
            g2d.fillRect(gridC * cellSize, gridR * cellSize, cellSize, cellSize)
        }

        if (showAgent && path != null && agentPositionIndex > 0 && agentPositionIndex <= path!!.size) {
            val (agentR, agentC) = path!![agentPositionIndex - 1]
            g2d.color = Color.MAGENTA
            g2d.fillRect(agentC * cellSize, agentR * cellSize, cellSize, cellSize)
        }

        g2d.color = Color.GREEN
        g2d.fillRect(1 * cellSize, 1 * cellSize, cellSize, cellSize)

        g2d.color = Color.RED
        g2d.fillRect((baseCols * 2 - 1) * cellSize, (baseRows * 2 - 1) * cellSize, cellSize, cellSize)
    }

    private inner class KeyboardListener : KeyAdapter() {
        override fun keyPressed(e: KeyEvent?) {
            if (e?.keyCode == KeyEvent.VK_SPACE) {
                if (!generating && !searching) {
                    startPathfinding()
                }
            }
        }
    }
}

fun main() {
    SwingUtilities.invokeLater {
        val frame = JFrame("Maze Generator with Dijkstra agent")
        frame.iconImage = Toolkit.getDefaultToolkit().getImage(MazePathfindingPanel::class.java.getResource("/icons/MazeGenerator.png"))
        frame.defaultCloseOperation = JFrame.EXIT_ON_CLOSE
        frame.isResizable = false
        frame.add(MazePathfindingPanel())
        frame.pack()
        frame.setLocationRelativeTo(null)
        frame.isVisible = true
    }
}