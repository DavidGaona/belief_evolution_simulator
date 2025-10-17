# Opinion Dynamics Simulator

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A scalable, high-performance simulator for studying opinion dynamics in social networks considering different social phenomenons. 
This implementation is based on our paper "The Spiral of Silence in Multi-Agent DeGroot models" where we examine how silence behaviors impact consensus formation in social networks.

## Overview

### The simulation currently supports the following foundational models:

- **Classical DeGroot**: Agents update their opinions by considering the weighted average of their neighbors.
- **FJ model (WIP)**: Agents update their opinions by considering their starting opinion and the weighted average of their neighbors, both weighted by some constant.
- **Bounded confidence (WIP)**: Agents update their opinions by only considering the weighted average of their neighbors inside a confidence range.

### Spiral of silence related models:

- **Silence Opinion Memoryless (SOM-)**: Agents update their opinions by considering only non-silent neighbors' opinions. Silent agents are excluded from the opinion update process.
- **Silence Opinion Memory-based (SOM+)**: Agents update their opinions considering all neighbors, but for silent neighbors, only their most recent expressed opinion is used.
- **Confidence Silence Opinion Memoryless (CSOM-)**: Agents update their opinions by considering only non-silent neighbors' opinions. Silent agents are excluded from the opinion update process.
- **Confidence Silence Opinion Memory-based (CSOM+)**: Agents update their opinions considering all neighbors, but for silent neighbors, only their most recent expressed opinion is used.


These models capture the "Spiral of Silence" theory from political science, which describes how individuals may withhold their opinions when they perceive themselves to be in the minority.

### Cognitive biases:

Each agent can also have a different cognitive bias for each neighbor.

- **Authority Bias**: Agents blindly follow perceived authorities, adjusting their opinion maximally towards the authority's stance, ignoring the actual magnitude of disagreement. 
- **Backfire Effect**: Agents react to disagreement, especially significant disagreement, by strengthening their original position, effectively moving away from the influencer's opinion.
- **Confirmation Bias**: Agents are more receptive to opinions closer to their own and pay less attention to or discount opinions that are significantly different.
- **Insular**: Agents completely ignore the opinions of others, remaining stubborn or closed-minded.

## Features

- Simulate opinion dynamics in networks of up to 134* million agents (2²⁷)
- Generate networks with small-world properties and power-law degree distributions
- Parallel computation support via Scala and Akka Actors
- Configurable parameters:
  - Tolerance radius
  - Majority threshold
  - Network density
  - Initial opinion distributions
- Comprehensive results logging for analysis


\* The max number of agents vary depending on agent type and system ram (64GB in our test case).

\*\* The current hard limit of agent network size would be 2,147,483,647 (2³¹ - 1) as we use 32 bit integers as pointer for each agent.

## Prerequisites

- Java JDK 17 or higher
- Scala 3 or higher
- SBT (Scala Build Tool) 1.5.x or higher
- PostgreSQL 17+ (for storing simulation results)

## Setup

1. Clone the repository:
   ```bash
   git clone https://github.com/YOUR_USERNAME/spiral-of-silence.git
   cd spiral-of-silence
   ```

2. Configure the database connection in `src/main/resources/application.conf`:
   ```hocon
   database {
     url = "jdbc:postgresql://localhost:5432/your_database"
     user = "your_username"
     password = "your_password"
   }
   ```

3. Build the project:
   ```bash
   sbt compile
   ```

## Running Simulations

To run simulations, first launch the application (e.g., via ``sbt run`` or executing the compiled JAR). You will be presented with a command-line interface (CLI).

### Basic Usage

-   Type ``help`` to see the list of available commands and their parameters.
-   Type ``exit``, ``quit``, or ``q`` to close the application.

### Running Generated Networks

Use the ``run`` command to quickly generate and simulate multiple networks based on statistical parameters.

**Syntax:**
``run [numNetworks] [numAgents] [density] [iterationLimit] [stopThreshold] [saveMode]``

**Parameters:**

-   ``numNetworks``: How many separate network simulations to run.
-   ``numAgents``: The number of agents within each generated network.
-   ``density``: Controls how interconnected the network is (higher value means more connections, exact interpretation depends on generation logic).
-   ``iterationLimit``: The maximum number of simulation steps (rounds) before stopping.
-   ``stopThreshold``: A minimum threshold for opinion change; the simulation stops if the overall change falls below this value.
-   ``saveMode``: Specifies how much simulation data to save (e.g., ``full``, ``standard``, ``agentless``). Type ``help`` in the CLI or see `CLI.scala` for all options.

**Example:**
```run 10 50 5 1000 0.001 standard```
*(This runs 10 networks, each with 50 agents, density 5, for up to 1000 iterations or until change is < 0.001, using the 'standard' save mode.)*

### Running Specific Network Configurations

Use the ``run-specific`` command to interactively define a network structure agent by agent, including their initial beliefs, connections, influence weights, and cognitive biases.

**Syntax:**
``run-specific``

The CLI will then prompt you for:

1.  Total number of agents.
2.  For each agent:
    -   Name (optional)
    -   Initial Belief
    -   Tolerance Radius & Offset (parameters likely related to a specific silence/interaction model not covered by the bias paper)
    -   Silence Strategy & Effect (also specific to your extended model)
    -   Number of outgoing connections.
3.  For each connection:
    -   Target agent index.
    -   Influence weight (0.0-1.0).
    -   Cognitive Bias type (DeGroot, Confirmation, Backfire, Authority, Insular).
4.  Overall network parameters (Iteration Limit, Stop Threshold, Network Name).

This mode gives you fine-grained control over the exact simulation setup.

## Citation

If you use this simulator in your research, please cite our paper:

```bibtex
@article{aranda2024soundsilencesocialnetworks,
      title={The Sound of Silence in Social Networks}, 
      author={Jesús Aranda and Juan Francisco Díaz and David Gaona and Frank Valencia},
      year={2024},
      eprint={2410.19685},
      archivePrefix={arXiv},
      primaryClass={cs.MA},
      url={https://arxiv.org/abs/2410.19685}, 
}
```

## Future Plans

- Docker Compose integration for easier setup and deployment
- Web-based visualization of simulation results
- Additional network topologies

## License

This project is licensed under the MIT License - see the [LICENSE](https://opensource.org/licenses/MIT) file for details.

## Acknowledgments

- Thanks to all contributors and reviewers of our research


```
belief_evolution_simulator
├─ build.sbt
├─ db
│  ├─ init
│  │  ├─ legacy_schema.sql
│  │  └─ schema.sql
│  ├─ init_db.sh
│  └─ postgres-entrypoint.sh
├─ docker-compose.yml
├─ Dockerfile
├─ Dockerfile_db
├─ project
│  └─ build.properties
├─ README.md
└─ src
   ├─ main
   │  ├─ resources
   │  │  └─ application.conf
   │  └─ scala
   │     ├─ benchmarking
   │     │  └─ rdtsc.scala
   │     ├─ CLI
   │     │  └─ CLI.scala
   │     ├─ core
   │     │  ├─ model
   │     │  │  ├─ agent
   │     │  │  │  └─ behavior
   │     │  │  │     ├─ bias
   │     │  │  │     │  └─ CognitiveBiases.scala
   │     │  │  │     └─ silence
   │     │  │  │        ├─ SilenceEffects.scala
   │     │  │  │        └─ SilenceStrategies.scala
   │     │  │  └─ AgentStates.scala
   │     │  └─ simulation
   │     │     ├─ actors
   │     │     │  ├─ AgentProcessor.scala
   │     │     │  ├─ Monitor.scala
   │     │     │  ├─ Network.scala
   │     │     │  └─ Run.scala
   │     │     └─ config
   │     │        ├─ AppMode.scala
   │     │        ├─ GlobalState.scala
   │     │        ├─ RunMode.scala
   │     │        └─ SaveModes.scala
   │     ├─ io
   │     │  ├─ db
   │     │  │  └─ DatabaseManager.scala
   │     │  ├─ persistence
   │     │  │  ├─ actors
   │     │  │  │  ├─ AgentStaticDataSaver.scala
   │     │  │  │  ├─ NeighborSaver.scala
   │     │  │  │  └─ RoundDataCollector.scala
   │     │  │  └─ RoundRouter.scala
   │     │  ├─ serialization
   │     │  │  └─ binary
   │     │  │     ├─ Decoder.scala
   │     │  │     ├─ Encoder.scala
   │     │  │     └─ EncodingTable.scala
   │     │  └─ web
   │     │     └─ Server.scala
   │     ├─ Main.scala
   │     └─ utils
   │        ├─ datastructures
   │        │  ├─ ArrayList.scala
   │        │  ├─ FenwickTree.scala
   │        │  ├─ SnowflakeID.scala
   │        │  └─ UUIDS.scala
   │        ├─ logging
   │        │  └─ Logger.scala
   │        ├─ rng
   │        │  ├─ distributions
   │        │  │  ├─ BimodalDistribution.scala
   │        │  │  └─ distributions.scala
   │        │  ├─ Xoshiro.scala
   │        │  └─ Xoshiro128.scala
   │        ├─ timers
   │        │  └─ CustomTimer.scala
   │        └─ Utilities.scala
   ├─ scripts
   │  ├─ exploration.R
   │  ├─ multi_round_analysis.R
   │  ├─ new_exploration.R
   │  └─ per_round_exploration.R
   └─ test
      └─ asm
         ├─ rdtsc.asm
         └─ rdtsc.def

```
```
belief_evolution_simulator
├─ build.sbt
├─ db
│  ├─ init
│  │  ├─ legacy_schema.sql
│  │  └─ schema.sql
│  ├─ init_db.sh
│  └─ postgres-entrypoint.sh
├─ docker-compose.yml
├─ Dockerfile
├─ Dockerfile_db
├─ project
│  └─ build.properties
├─ README.md
└─ src
   ├─ main
   │  ├─ resources
   │  │  └─ application.conf
   │  └─ scala
   │     ├─ benchmarking
   │     │  └─ rdtsc.scala
   │     ├─ CLI
   │     │  └─ CLI.scala
   │     ├─ core
   │     │  ├─ model
   │     │  │  ├─ agent
   │     │  │  │  └─ behavior
   │     │  │  │     ├─ bias
   │     │  │  │     │  └─ CognitiveBiases.scala
   │     │  │  │     └─ silence
   │     │  │  │        ├─ SilenceEffects.scala
   │     │  │  │        └─ SilenceStrategies.scala
   │     │  │  └─ AgentStates.scala
   │     │  └─ simulation
   │     │     ├─ actors
   │     │     │  ├─ AgentProcessor.scala
   │     │     │  ├─ Monitor.scala
   │     │     │  ├─ Network.scala
   │     │     │  └─ Run.scala
   │     │     └─ config
   │     │        ├─ AppMode.scala
   │     │        ├─ GlobalState.scala
   │     │        ├─ RunMode.scala
   │     │        └─ SaveModes.scala
   │     ├─ io
   │     │  ├─ db
   │     │  │  └─ DatabaseManager.scala
   │     │  ├─ persistence
   │     │  │  ├─ actors
   │     │  │  │  ├─ AgentStaticDataSaver.scala
   │     │  │  │  ├─ NeighborSaver.scala
   │     │  │  │  └─ RoundDataCollector.scala
   │     │  │  └─ RoundRouter.scala
   │     │  ├─ serialization
   │     │  │  └─ binary
   │     │  │     ├─ Decoder.scala
   │     │  │     ├─ Encoder.scala
   │     │  │     └─ EncodingTable.scala
   │     │  └─ web
   │     │     └─ Server.scala
   │     ├─ Main.scala
   │     └─ utils
   │        ├─ datastructures
   │        │  ├─ ArrayList.scala
   │        │  ├─ FenwickTree.scala
   │        │  ├─ SnowflakeID.scala
   │        │  └─ UUIDS.scala
   │        ├─ logging
   │        │  └─ Logger.scala
   │        ├─ rng
   │        │  ├─ distributions
   │        │  │  ├─ BimodalDistribution.scala
   │        │  │  └─ distributions.scala
   │        │  ├─ Xoshiro.scala
   │        │  └─ Xoshiro128.scala
   │        ├─ timers
   │        │  └─ CustomTimer.scala
   │        └─ Utilities.scala
   ├─ scripts
   │  ├─ exploration.R
   │  ├─ multi_round_analysis.R
   │  ├─ new_exploration.R
   │  └─ per_round_exploration.R
   └─ test
      └─ asm
         ├─ rdtsc.asm
         └─ rdtsc.def

```