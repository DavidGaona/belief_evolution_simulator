package core.simulation.config

// Main save mode trait
sealed trait SaveMode {
    def allowsNeighborless: Boolean
    def includesFirstRound: Boolean
    def includesRounds: Boolean
    def includesLastRound: Boolean
    def includesAgents: Boolean
    def includesNeighbors: Boolean
    def includesNetworks: Boolean
    def includesAgentTypes: Boolean
    def savesToDB: Boolean
}

// Neighborless modifier
case class NeighborlessMode(baseMode: SaveMode) extends SaveMode {
    require(baseMode.allowsNeighborless, s"${baseMode.getClass.getSimpleName} does not support Neighborless mode")
    
    def allowsNeighborless: Boolean = true
    def includesFirstRound: Boolean = baseMode.includesFirstRound
    def includesRounds: Boolean = baseMode.includesRounds
    def includesLastRound: Boolean = baseMode.includesLastRound
    def includesAgents: Boolean = baseMode.includesAgents
    def includesNeighbors: Boolean = false
    def includesNetworks: Boolean = baseMode.includesNetworks
    def includesAgentTypes: Boolean = baseMode.includesAgentTypes
    def savesToDB: Boolean = baseMode.savesToDB
}

sealed trait BaseMode extends SaveMode {
    def allowsNeighborless: Boolean = this != Full
    def includesNeighbors: Boolean = true
}

case object Full extends BaseMode {
    def includesFirstRound: Boolean = true
    def includesRounds: Boolean = true
    def includesLastRound: Boolean = false
    def includesAgents: Boolean = true
    def includesNetworks: Boolean = true
    def includesAgentTypes: Boolean = true
    def savesToDB: Boolean = true
}

case class RoundSampling(sampleInterval: Int) extends BaseMode {
    require(sampleInterval > 0, "Sampling interval must be positive")
    def includesFirstRound: Boolean = true
    def includesRounds: Boolean = true
    def includesLastRound: Boolean = false
    def includesAgents: Boolean = true
    def includesNetworks: Boolean = true
    def includesAgentTypes: Boolean = true
    def savesToDB: Boolean = true
}

case object Standard extends BaseMode {
    def includesFirstRound: Boolean = true
    def includesRounds: Boolean = false
    def includesLastRound: Boolean = true
    def includesAgents: Boolean = true
    def includesNetworks: Boolean = true
    def includesAgentTypes: Boolean = true
    def savesToDB: Boolean = true
}

case object StandardLight extends BaseMode {
    def includesFirstRound: Boolean = true
    def includesRounds: Boolean = false
    def includesLastRound: Boolean = false
    def includesAgents: Boolean = true
    def includesNetworks: Boolean = true
    def includesAgentTypes: Boolean = true
    def savesToDB: Boolean = true
}

case object Roundless extends BaseMode {
    def includesFirstRound: Boolean = false
    def includesRounds: Boolean = false
    def includesLastRound: Boolean = false
    def includesAgents: Boolean = true
    def includesNetworks: Boolean = true
    def includesAgentTypes: Boolean = true
    def savesToDB: Boolean = true
}

case object AgentlessTyped extends BaseMode {
    def includesFirstRound: Boolean = false
    def includesRounds: Boolean = false
    def includesLastRound: Boolean = false
    def includesAgents: Boolean = false
    override def includesNeighbors: Boolean = false  
    def includesNetworks: Boolean = true
    def includesAgentTypes: Boolean = true
    def savesToDB: Boolean = true
}

case object Agentless extends BaseMode {
    def includesFirstRound: Boolean = false
    def includesRounds: Boolean = false
    def includesLastRound: Boolean = false
    def includesAgents: Boolean = false
    override def includesNeighbors: Boolean = false  
    def includesNetworks: Boolean = true
    def includesAgentTypes: Boolean = false
    def savesToDB: Boolean = true
}

case object Performance extends BaseMode {
    def includesFirstRound: Boolean = false
    def includesRounds: Boolean = false
    def includesLastRound: Boolean = false
    def includesAgents: Boolean = false
    override def includesNeighbors: Boolean = false
    def includesNetworks: Boolean = false
    def includesAgentTypes: Boolean = false
    def savesToDB: Boolean = true
}

case object Debug extends BaseMode {
    def includesFirstRound: Boolean = false
    def includesRounds: Boolean = false
    def includesLastRound: Boolean = false
    def includesAgents: Boolean = false
    override def includesNeighbors: Boolean = false
    def includesNetworks: Boolean = false
    def includesAgentTypes: Boolean = false
    def savesToDB: Boolean = false
}

object SaveMode {
    def withNeighborless(mode: SaveMode): SaveMode = {
        if (mode.allowsNeighborless) NeighborlessMode(mode) else mode
    }
    
    def isValidConfiguration(mode: SaveMode): Boolean = {
        true
    }
    
}
