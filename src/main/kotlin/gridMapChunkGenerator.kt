package org.lewapnoob.gridMap

import java.awt.Color
import java.util.Random
import kotlin.math.floor
import kotlin.math.sqrt

class ChunkGenerator(
    private val seed: Int,
    private val oreColors: MutableSet<Int>
) {
    private val noise = PerlinNoise(seed)
    private val caveNoise = PerlinNoise(seed + 1)

    // Constants
    private val BLOCK_ID_AIR = 0
    private val BLOCK_ID_LIGHT = 2
    private val BLOCK_ID_LAVA = 3

    // Models (Assuming these are available in the package)
    private val treeModel = treeModelData
    private val DungeonModel = DungeonModelData
    private val IglooModel = IglooModelData

    fun generate(cx: Int, cz: Int): Chunk {
        val chunk = Chunk(cx, cz)

        // 1. Generowanie terenu i jaskiń
        for (lx in 0 until 16) {
            for (lz in 0 until 16) {
                val wx = cx * 16 + lx
                val wz = cz * 16 + lz
                val h = getTerrainHeight(wx, wz)

                for (y in 0..127) {
                    val blockColor = computeWorldBlock(wx, y, wz, h)
                    if (blockColor != BLOCK_ID_AIR) {
                        chunk.setBlock(lx, y, lz, blockColor)
                    }
                }
            }
        }

        // 2. Generowanie rud
        generateOres(chunk, cx, cz)

        // 3. Generowanie jezior lawy
        generateLavaLakes(chunk, cx, cz)

        // 4. Generowanie struktur
        generateStructureType(chunk, cx, cz, treeModel, 0.004, 50, 128, Color(0x59A608).rgb, 1)
        generateStructureType(chunk, cx, cz, DungeonModel, 0.0001, 0, 30, Color(0x8EA3A1).rgb, 1, true, listOf(0, 90, 180, 270))
        generateStructureType(chunk, cx, cz, IglooModel, 0.00001, 50, 80, Color(0x59A608).rgb, 0, false, listOf(0, 90, 180, 270))

        chunk.modified = false
        return chunk
    }

    fun getTerrainHeight(wx: Int, wz: Int): Int {
        val n = noise.noise(wx * 0.02, wz * 0.02)
        return (58 + n * 6).toInt().coerceIn(0, 127)
    }

    private fun computeWorldBlock(wx: Int, wy: Int, wz: Int, precalcHeight: Int? = null): Int {
        if (wy < 0) return BLOCK_ID_AIR
        if (wy == 0) return Color.BLACK.rgb // Bedrock

        val h = precalcHeight ?: getTerrainHeight(wx, wz)
        if (wy > h) return BLOCK_ID_AIR

        val baseColor = when {
            wy == h -> Color(0x59A608).rgb
            wy > h - 4 -> Color(0x6c3c0c).rgb
            else -> Color(0x8EA3A1).rgb
        }

        if (wy == 1) return baseColor

        val baseCaveThreshold = 0.65
        val surfaceOpeningResistance = 0.2
        val frequency = 0.07
        val noiseVal = caveNoise.noise(wx * frequency, wy * frequency * 2, wz * frequency)

        val depth = h - wy
        val threshold = if (depth < 5) {
            baseCaveThreshold + surfaceOpeningResistance * (1.0 - depth / 5.0)
        } else {
            baseCaveThreshold
        }

        if (noiseVal > threshold) return BLOCK_ID_AIR

        return baseColor
    }

    private fun generateOres(chunk: Chunk, cx: Int, cz: Int) {
        val rand = Random((cx * 341873128712L + cz * 132897987541L + seed).hashCode().toLong())
        val targetBlock = Color(0x8EA3A1).rgb

        generateOreType(chunk, rand, targetBlock, Color(0x151716).rgb, 1, 10, 20, 64, 32)
        generateOreType(chunk, rand, targetBlock, Color(0xe3c0aa).rgb, 1, 5, 1, 50, 13)
        generateOreType(chunk, rand, targetBlock, Color(0x30ddeb).rgb, 1, 4, 1, 16, 5)
    }

    private fun generateOreType(chunk: Chunk, rand: Random, target: Int, color: Int, minSize: Int, maxSize: Int, minY: Int, maxY: Int, maxVeins: Int) {
        oreColors.add(color)

        val veinsCount = rand.nextInt(maxVeins + 1)
        for (i in 0 until veinsCount) {
            val startX = rand.nextInt(16)
            val startZ = rand.nextInt(16)
            val startY = minY + rand.nextInt(maxY - minY + 1)

            if (chunk.getBlock(startX, startY, startZ) == target) {
                val size = minSize + rand.nextInt(maxSize - minSize + 1)
                placeOreVein(chunk, rand, startX, startY, startZ, size, target, color, minY, maxY)
            }
        }
    }

    private fun placeOreVein(chunk: Chunk, rand: Random, x: Int, y: Int, z: Int, size: Int, target: Int, color: Int, minY: Int, maxY: Int) {
        val vein = java.util.ArrayList<BlockPos>()
        vein.add(BlockPos(x, y, z))
        chunk.setBlock(x, y, z, color)

        var currentSize = 1
        var attempts = 0
        while (currentSize < size && attempts < size * 4) {
            attempts++
            val source = vein[rand.nextInt(vein.size)]

            val dir = rand.nextInt(6)
            var dx = 0; var dy = 0; var dz = 0
            when(dir) {
                0 -> dx = 1; 1 -> dx = -1
                2 -> dy = 1; 3 -> dy = -1
                4 -> dz = 1; 5 -> dz = -1
            }

            val nx = source.x + dx
            val ny = source.y + dy
            val nz = source.z + dz

            if (nx in 0..15 && nz in 0..15 && ny in minY..maxY) {
                if (chunk.getBlock(nx, ny, nz) == target) {
                    chunk.setBlock(nx, ny, nz, color)
                    vein.add(BlockPos(nx, ny, nz))
                    currentSize++
                }
            }
        }
    }

    private fun generateLavaLakes(chunk: Chunk, cx: Int, cz: Int) {
        for (lx in -8..23) {
            for (lz in -8..23) {
                val wx = cx * 16 + lx
                val wz = cz * 16 + lz

                if (isLakeCenter(wx, wz)) {
                    val rand = Random((wx * 341873128712L + wz * 132897987541L + seed).hashCode().toLong())
                    val surfaceY = rand.nextInt(10) + 4
                    val radius = rand.nextDouble() * 3.0 + 2.5
                    val maxDepth = rand.nextDouble() * 3.0 + 2.0
                    placeLake(chunk, lx, surfaceY, lz, radius, maxDepth)
                }
            }
        }
    }

    private fun isLakeCenter(wx: Int, wz: Int): Boolean {
        val hash = (wx * 73856093 xor wz * 19349663 xor seed).toString().hashCode()
        val random = Random(hash.toLong())
        return random.nextDouble() < 0.0005
    }

    private fun placeLake(chunk: Chunk, centerLx: Int, surfaceY: Int, centerLz: Int, radius: Double, maxDepth: Double) {
        val stoneColor = Color(0x8EA3A1).rgb
        val margin = 5.0
        val minX = (centerLx - radius - margin).toInt().coerceIn(0, 15)
        val maxX = (centerLx + radius + margin).toInt().coerceIn(0, 15)
        val minZ = (centerLz - radius - margin).toInt().coerceIn(0, 15)
        val maxZ = (centerLz + radius + margin).toInt().coerceIn(0, 15)
        val minY = (surfaceY - maxDepth - 3).toInt().coerceIn(1, 127)
        val maxY = (surfaceY + 2).toInt().coerceIn(0, 127)

        for (x in minX..maxX) {
            for (z in minZ..maxZ) {
                val dx = x - centerLx
                val dz = z - centerLz
                val distSq = dx * dx + dz * dz
                val dist = sqrt(distSq.toDouble())

                val wx = chunk.x * 16 + x
                val wz = chunk.z * 16 + z

                val shapeNoise = noise.noise(wx * 0.2, wz * 0.2)
                val effectiveRadius = radius + (shapeNoise * 2.0)
                val wallRadius = effectiveRadius + 1.5

                val noiseVal = noise.noise(wx * 0.3, wz * 0.3)
                val localDepth = maxDepth - (noiseVal * 1.5).coerceAtLeast(0.0)

                for (y in minY..maxY) {
                    if (effectiveRadius > 0 && dist < effectiveRadius) {
                        if (y <= surfaceY && y >= surfaceY - localDepth) {
                            chunk.setBlock(x, y, z, BLOCK_ID_LAVA)
                            chunk.setMeta(x, y, z, 8)
                        } else if (y > surfaceY && y <= surfaceY + 1) {
                            if (chunk.getBlock(x, y, z) != 0) chunk.setBlock(x, y, z, 0)
                        } else if (y < surfaceY - localDepth) {
                            if (chunk.getBlock(x, y, z) == 0) chunk.setBlock(x, y, z, stoneColor)
                        }
                    } else if (wallRadius > 0 && dist < wallRadius) {
                        if (y <= surfaceY && y >= surfaceY - localDepth) {
                            if (chunk.getBlock(x, y, z) == 0) {
                                chunk.setBlock(x, y, z, stoneColor)
                            }
                        }
                    }
                }
            }
        }
    }

    private fun generateStructureType(chunk: Chunk, cx: Int, cz: Int, model: List<ModelVoxel>, density: Double, minH: Int, maxH: Int, targetBlock: Int, yOffset: Int, clearSpace: Boolean = false, allowedRotations: List<Int> = listOf(0)) {
        val margin = 10
        val modelCache = mutableMapOf<Int, List<ModelVoxel>>()

        for (lx in -margin..16 + margin) {
            for (lz in -margin..16 + margin) {
                val wx = cx * 16 + lx
                val wz = cz * 16 + lz

                if (isStructureAt(wx, wz, density, model.hashCode())) {
                    val validYs = mutableListOf<Int>()
                    val startY = maxOf(minH, 0)
                    val endY = minOf(maxH, 127)

                    for (y in startY..endY) {
                        if (computeWorldBlock(wx, y, wz) == targetBlock && computeWorldBlock(wx, y + 1, wz) == BLOCK_ID_AIR) {
                            validYs.add(y)
                        }
                    }

                    if (validYs.isNotEmpty()) {
                        val hash = (wx * 73856093 xor wz * 19349663 xor seed xor model.hashCode()).toString().hashCode()
                        val random = Random(hash.toLong())
                        random.nextDouble()

                        val selectedY = validYs[random.nextInt(validYs.size)]
                        val rotation = if (allowedRotations.isNotEmpty()) allowedRotations[random.nextInt(allowedRotations.size)] else 0
                        val finalModel = modelCache.getOrPut(rotation) { rotateModel(model, rotation) }

                        placeStructure(chunk, lx, selectedY + yOffset, lz, finalModel, clearSpace)
                    }
                }
            }
        }
    }

    private fun rotateModel(model: List<ModelVoxel>, angle: Int): List<ModelVoxel> {
        var normAngle = angle % 360
        if (normAngle < 0) normAngle += 360
        val steps = (normAngle / 90) % 4
        if (steps == 0) return model
        return model.map { voxel ->
            var x = voxel.x
            var z = voxel.z
            repeat(steps) {
                val oldX = x
                val oldZ = z
                x = -oldZ
                z = oldX
            }
            ModelVoxel(x, voxel.y, z, voxel.color, voxel.isVoid)
        }
    }

    private fun isStructureAt(wx: Int, wz: Int, density: Double, salt: Int): Boolean {
        val hash = (wx * 73856093 xor wz * 19349663 xor seed xor salt).toString().hashCode()
        val random = Random(hash.toLong())
        return random.nextDouble() < density
    }

    private fun placeStructure(chunk: Chunk, rootLx: Int, rootY: Int, rootLz: Int, model: List<ModelVoxel>, clearSpace: Boolean) {
        if (clearSpace && model.isNotEmpty()) {
            val minX = model.minOf { it.x }
            val maxX = model.maxOf { it.x }
            val minY = model.minOf { it.y }
            val maxY = model.maxOf { it.y }
            val minZ = model.minOf { it.z }
            val maxZ = model.maxOf { it.z }

            val voxelMap = model.associate { Triple(it.x, it.y, it.z) to it }

            for (x in minX..maxX) {
                for (y in minY..maxY) {
                    for (z in minZ..maxZ) {
                        val tx = rootLx + x
                        val ty = rootY + y
                        val tz = rootLz + z

                        if (tx in 0 until 16 && tz in 0 until 16 && ty in 0 until 128) {
                            val voxel = voxelMap[Triple(x, y, z)]
                            if (voxel != null) {
                                if (!voxel.isVoid) {
                                    chunk.setBlock(tx, ty, tz, voxel.color.rgb)
                                }
                            } else {
                                chunk.setBlock(tx, ty, tz, 0)
                            }
                        }
                    }
                }
            }
        } else {
            for (voxel in model) {
                val tx = rootLx + voxel.x
                val ty = rootY + voxel.y
                val tz = rootLz + voxel.z

                if (tx in 0 until 16 && tz in 0 until 16 && ty in 0 until 128) {
                    if (voxel.isVoid) {
                        chunk.setBlock(tx, ty, tz, 0)
                    } else {
                        chunk.setBlock(tx, ty, tz, voxel.color.rgb)
                    }
                }
            }
        }
    }
}

class PerlinNoise(seed: Int) {
    private val p = IntArray(512)
    init {
        val random = Random(seed.toLong())
        val permutation = (0..255).toMutableList()
        permutation.shuffle(random)
        for (i in 0..255) {
            p[i] = permutation[i]
            p[i + 256] = permutation[i]
        }
    }

    fun noise(x: Double, y: Double): Double {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()

        val X = xi and 255
        val Y = yi and 255

        val xf = x - xi
        val yf = y - yi

        val u = fade(xf)
        val v = fade(yf)
        val aa = p[p[X] + Y]; val ab = p[p[X] + Y + 1]
        val ba = p[p[X + 1] + Y]; val bb = p[p[X + 1] + Y + 1]
        return lerp(v, lerp(u, grad(p[aa], xf, yf), grad(p[ba], xf - 1, yf)),
            lerp(u, grad(p[ab], xf, yf - 1), grad(p[bb], xf - 1, yf - 1)))
    }

    fun noise(x: Double, y: Double, z: Double): Double {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val zi = floor(z).toInt()

        val X = xi and 255
        val Y = yi and 255
        val Z = zi and 255

        val xf = x - xi
        val yf = y - yi
        val zf = z - zi

        val u = fade(xf)
        val v = fade(yf)
        val w = fade(zf)

        val A = p[X] + Y
        val AA = p[A] + Z
        val AB = p[A + 1] + Z
        val B = p[X + 1] + Y
        val BA = p[B] + Z
        val BB = p[B + 1] + Z

        val res = lerp(w,
            lerp(v,
                lerp(u, grad(p[AA], xf, yf, zf), grad(p[BA], xf - 1, yf, zf)),
                lerp(u, grad(p[AB], xf, yf - 1, zf), grad(p[BB], xf - 1, yf - 1, zf))
            ),
            lerp(v,
                lerp(u, grad(p[AA + 1], xf, yf, zf - 1), grad(p[BA + 1], xf - 1, yf, zf - 1)),
                lerp(u, grad(p[AB + 1], xf, yf - 1, zf - 1), grad(p[BB + 1], xf - 1, yf - 1, zf - 1))
            )
        )
        return (res + 1) / 2.0 // Bring to 0..1 range
    }
    private fun fade(t: Double) = t * t * t * (t * (t * 6 - 15) + 10)
    private fun lerp(t: Double, a: Double, b: Double) = a + t * (b - a)
    private fun grad(hash: Int, x: Double, y: Double) = if (hash and 1 == 0) x else -x + if (hash and 2 == 0) y else -y
    private fun grad(hash: Int, x: Double, y: Double, z: Double): Double {
        // Standardowa implementacja gradientu dla 3D Perlin Noise
        val h = hash and 15
        val u = if (h < 8) x else y
        val v = if (h < 4) y else if (h == 12 || h == 14) x else z
        return (if (h and 1 != 0) -u else u) + if (h and 2 != 0) -v else v
    }
}