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

- [Docker](https://docs.docker.com/get-docker/) and [Docker Compose](https://docs.docker.com/compose/install/) (v2 recommended)
- A [Firebase](https://firebase.google.com/) project with **Authentication** enabled
- The Firebase service account JSON downloaded from **Firebase Console → Project Settings → Service Accounts → Generate new private key**

## Setup with Docker

### 1. Clone the repository

```bash
git clone https://github.com/YOUR_USERNAME/belief_evolution_simulator.git
cd belief_evolution_simulator
```

### 2. Create the `.env` file

Copy the template below into a file named `.env` at the project root and fill in the values marked with `<...>`:

```dotenv
# ── PostgreSQL ────────────────────────────────────────────────────────────────
POSTGRES_DB=promueva
POSTGRES_USER=postgres
POSTGRES_PASSWORD=<choose-a-strong-password>

POSTGRES_DB_LEGACY=promueva_legacy
POSTGRES_USER_LEGACY=postgres
POSTGRES_PASSWORD_LEGACY=<choose-a-strong-password>

# ── Backend → DB ──────────────────────────────────────────────────────────────
DB_HOST=postgres
DB_PORT=5432
DB_HOST_LEGACY=postgres
DB_PORT_LEGACY=5432

# ── Server ────────────────────────────────────────────────────────────────────
SERVER_HOST=0.0.0.0
SERVER_PORT=9000

# ── App flags ─────────────────────────────────────────────────────────────────
APP_SKIP_DATABASE=false
APP_SERVER_MODE=true
APP_LOCAL_MODE=false
APP_LEGACY_DB=false
APP_SERVER_LOGS=false
APP_GENERAL_LOGS=true
APP_SIMULATION_LOGS=false
APP_SKIP_WS=false

# ── Firebase ──────────────────────────────────────────────────────────────────
# Path of the service account JSON inside the container (keep as-is)
GOOGLE_APPLICATION_CREDENTIALS=/secrets/firebase-sa.json

# Your Firebase project ID (Firebase Console → Project Settings → General)
FIREBASE_PROJECT_ID=<your-firebase-project-id>

# ── Bootstrap admin ───────────────────────────────────────────────────────────
# Comma-separated list of emails that get the Administrator role automatically
# on first login. Add at least your own email here.
BOOTSTRAP_ADMIN_EMAILS=<your-email@domain.com>

# ── Firebase service account on the HOST machine ──────────────────────────────
# Absolute path to the JSON file you downloaded from Firebase Console.
FIREBASE_SA_HOST_PATH=/absolute/path/to/firebase-sa.json
```

### 3. Fix the Firebase volume mount in `docker-compose.yml`

The `backend` service mounts the service account file. Update the `volumes` entry to use `FIREBASE_SA_HOST_PATH`:

```yaml
    volumes:
      - ${FIREBASE_SA_HOST_PATH}:/secrets/firebase-sa.json:ro
```

> If `docker-compose.yml` still has a hard-coded path (e.g. `/home/krud3/secrets/...`), replace it with the line above.

### 4. Build and start the stack

```bash
docker-compose down          # stop any old containers first
docker-compose build         # compile the Scala app (takes ~2-5 min on first run)
docker-compose up -d         # start postgres + backend in the background
```

Watch the logs to confirm startup:

```bash
docker-compose logs -f backend
# Expected output:
#   [info] done compiling
#   [info] Server online at http://0:0:0:0:0:0:0:0:9000/
```

### 5. Database migrations

Migrations run **automatically** on every startup. The init script applies:

| File | What it does |
|---|---|
| `db/init/schema.sql` | Creates all tables for the main DB |
| `db/init/legacy_schema.sql` | Creates tables for the legacy DB |
| `db/init/migrations/*.sql` | Incremental changes (auth schema, usage tracking, …) — all idempotent |

No manual step is needed.

### 6. Create the first admin user

The server uses Firebase Authentication for all protected endpoints. To get an ID token:

1. In your Firebase project, enable the **Email/Password** sign-in provider (or Google, etc.).
2. Create a user in **Firebase Console → Authentication → Users**, or register via the Firebase client SDK.
3. Obtain an ID token for that user (see [Firebase REST API](https://firebase.google.com/docs/reference/rest/auth#section-sign-in-email-password) or use the Firebase JS/Android/iOS SDK).

Once you have the token, call `POST /api/users/sync` — this registers the user in the local DB and, if the email is in `BOOTSTRAP_ADMIN_EMAILS`, automatically grants the **Administrator** role:

```bash
TOKEN="<firebase-id-token>"
curl -s -X POST http://localhost:9000/api/users/sync \
  -H "Authorization: Bearer $TOKEN"
```

You only need to do this once per user. Subsequent logins auto-upsert the user record.

### 7. Explore the API

Interactive API docs (Swagger UI) are available at:

```
http://localhost:9000/docs
```

The raw OpenAPI spec is at `http://localhost:9000/openapi.yaml`.

---

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

- Web-based visualization of simulation results
- Additional network topologies

## License

This project is licensed under the MIT License - see the [LICENSE](https://opensource.org/licenses/MIT) file for details.

## Acknowledgments

- Thanks to all contributors and reviewers of our research

