package com.predictrivals.roundrobin

/**
 * Standard round-robin circle method, doubled (2 legs — every pair plays twice). Returns one
 * entry per matchday, in order (index 0 = round_number 1, ...); each matchday is a list of
 * (playerA, playerB) pairs. playerB null marks a bye for playerA that matchday (only possible
 * when the player count is odd).
 */
object RoundRobinScheduler {

    fun generate(playerIds: List<Long>): List<List<Pair<Long, Long?>>> {
        require(playerIds.size >= 2) { "Round-robin needs at least 2 players" }
        val singleLeg = generateSingleLeg(playerIds)
        return singleLeg + singleLeg
    }

    private fun generateSingleLeg(playerIds: List<Long>): List<List<Pair<Long, Long?>>> {
        val slots: MutableList<Long?> = playerIds.toMutableList()
        if (slots.size % 2 != 0) slots.add(null)
        val n = slots.size
        val rounds = n - 1
        val half = n / 2

        val result = mutableListOf<List<Pair<Long, Long?>>>()
        repeat(rounds) {
            val roundPairs = mutableListOf<Pair<Long, Long?>>()
            for (i in 0 until half) {
                val a = slots[i]
                val b = slots[n - 1 - i]
                when {
                    a != null && b != null -> roundPairs += (a to b)
                    a != null -> roundPairs += (a to null)
                    b != null -> roundPairs += (b to null)
                }
            }
            result += roundPairs

            // Rotate all but the first slot one position clockwise.
            val fixed = slots[0]
            val rotating = slots.subList(1, n)
            val last = rotating.removeAt(rotating.size - 1)
            rotating.add(0, last)
            slots[0] = fixed
            for (i in 1 until n) slots[i] = rotating[i - 1]
        }
        return result
    }
}
