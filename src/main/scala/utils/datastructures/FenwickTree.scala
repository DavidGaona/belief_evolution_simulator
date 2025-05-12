package utils.datastructures

import scala.util.Random

class FenwickTree(size: Int, density: Int, setValue: Double, seed: Long, countFreqs: Boolean = false) {
    private val tree = Array.ofDim[Double](size + 1) //Array.fill(8)(0)
    private val scoresArr = Array.ofDim[Double](size + 1)
    private val freqs = Array.ofDim[Double](size + 1)
    private val minScore: Double = density + (density * setValue)
    private var curMax: Double = (density + 1) * minScore
    private var curLength: Int = density + 1
    private val random = new Random(seed)
    
    for (i <- 0 until (density + 1)) {
        update(i: Int, minScore: Double)
    }
    
    private def update(i: Int, score: Double): Unit = {
        scoresArr(i + 1) += score
        updateTree(i, score)
    }
    
    // O(log2(n))
    private def updateTree(i: Int, score: Double): Unit = {
        var index = i + 1
        while (index <= size) {
            tree(index) += score
            index += index & -index
        }
    }
    
    // Compute the prefix sum from 1 to `i` O(log2(n))
    private def query(i: Int): Double = {
        var sum = 0.0
        var index = i + 1
        while (index > 0) {
            sum += tree(index)
            index -= index & -index
        }
        sum
    }
    
    // Find the position for a given value using binary search O(log2(n)^2)
    private def findRange(value: Double): Int = {
        var low = 0
        var high = curLength - 1
        while (low < high) {
            val mid = (low + high) / 2
            val queryVal = query(mid)
            if (queryVal < value) low = mid + 1
            else high = mid
        }
        low
    }
    
    def initWithScores(scores: Array[Double]): Unit = {
        curLength = scores.length
        for (i <- scores.indices) {
            update(i, scores(i))
        }
        curMax = query(scores.length)
    }
    
    // O(d * log2(n)^2 + d * log2(n) + log2(n))
    def pickRandoms(): Array[Int] = {
        val nodesPicked = Array.ofDim[Int](density)
        var curLocalMax = curMax
        var i = 0
        while (i < density) {
            nodesPicked(i) = findRange(random.nextDouble() * curLocalMax)
            curLocalMax -= scoresArr(nodesPicked(i) + 1)
            updateTree(nodesPicked(i), -scoresArr(nodesPicked(i) + 1))
            i += 1
        }
        
        // Reassign the values and correct for index moved values
        i = 0
        while (i < density) {
            scoresArr(nodesPicked(i) + 1) += 1
            updateTree(nodesPicked(i), scoresArr(nodesPicked(i) + 1))
            if (countFreqs) freqs(nodesPicked(i) + 1) += 1
            i += 1
        }
        
        curMax += minScore + density
        curLength += 1
        update(curLength - 1, minScore)
        nodesPicked
    }
    
    def pickRandomsInto(arr: Array[Int], indexStart: Int): Unit = {
        var curLocalMax = curMax

        var i = 0
        while (i < density) {
            arr(i + indexStart) = findRange(random.nextDouble() * curLocalMax)
            curLocalMax -= scoresArr(arr(i + indexStart) + 1)
            updateTree(arr(i + indexStart), -scoresArr(arr(i + indexStart) + 1))
            i += 1
        }
        
        // Reassign the values and correct for index moved values
        i = 0
        while (i < density) {
            scoresArr(arr(i + indexStart) + 1) += 1
            updateTree(arr(i + indexStart), scoresArr(arr(i + indexStart) + 1))
            if (countFreqs) freqs(arr(i + indexStart) + 1) += 1
            i += 1
        }
        
        curMax += minScore + density
        curLength += 1
        update(curLength - 1, minScore)
    }
    
}


object FenwickTest extends App {
    val fenwickTree = new FenwickTree(5, 3, 2.5, 41)
//    println(fenwickTree.tree.mkString("Array(", ", ", ")"))
//    println(fenwickTree.scoresArr.mkString("Array(", ", ", ")"))
//    println(fenwickTree.query(0))
//    println(fenwickTree.query(1))
//    println(fenwickTree.query(2))
//    println(fenwickTree.query(3))
//    println(fenwickTree.query(4))
//    // picks
//    val chosen_ones = fenwickTree.pickRandoms()
//    println(chosen_ones.mkString("Array(", ", ", ")"))
//    println(fenwickTree.tree.mkString("Array(", ", ", ")"))
//    println(fenwickTree.scoresArr.mkString("Array(", ", ", ")"))
//
//    println(fenwickTree.findRange(10.5))
//    println(fenwickTree.query(0))
//    println(fenwickTree.query(1))
//    println(fenwickTree.query(2))
//    println(fenwickTree.query(3))
//    println(fenwickTree.query(4))
//    // Second guy
//    val chosen_ones_2 = fenwickTree.pickRandoms()
//    println(chosen_ones_2.mkString("Array(", ", ", ")"))
//    println(fenwickTree.tree.mkString("Array(", ", ", ")"))
//    println(fenwickTree.scoresArr.mkString("Array(", ", ", ")"))
//
//    println(fenwickTree.findRange(10.5))
//    println(fenwickTree.query(0))
//    println(fenwickTree.query(1))
//    println(fenwickTree.query(2))
//    println(fenwickTree.query(3))
//    println(fenwickTree.query(4))

}
