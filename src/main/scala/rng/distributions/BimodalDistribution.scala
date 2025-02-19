package rng.distributions

import scala.util.Random

class BimodalDistribution(peak1: Double, peak2: Double, lower: Double = 0, upper: Double = 1) {
  private val random = new Random()
  private val midpoint = (peak1 + peak2) / 2
  private val stdDev1 = (midpoint - lower) / 3
  private val stdDev2 = (upper - midpoint) / 3

  private def clamp(value: Double, min: Double, max: Double): Double = {
    Math.max(min, Math.min(value, max))
  }

  private def sampleGaussian(peak: Double, stdDev: Double): Double = {
    clamp(peak + random.nextGaussian() * stdDev, lower, upper)
  }

  def sample(): Double = {
    if (random.nextFloat() < 0.5f) sampleGaussian(peak1, stdDev1)
    else sampleGaussian(peak2, stdDev2)
  }
  
  def sampleFloat(): Float = {
    if (random.nextBoolean()) sampleGaussian(peak1, stdDev1).toFloat
    else sampleGaussian(peak2, stdDev2).toFloat
  }
}
