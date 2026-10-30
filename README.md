# Smart Ambulance Network

**Ambulance Traffic Management**, not just ambulance tracking.

```
Patient -> Hospital -> Ambulance -> Cloud Server -> Traffic Police -> Traffic Signals -> Road Users -> Hospital
```

A patient asks for help. The system ranks nearby hospitals (bed count, speciality, travel time) and offers the request to the best one. When the hospital accepts, the nearest free ambulance is dispatched. The cloud server plans the route, predicts when the ambulance reaches each junction, and asks the traffic police to approve a "green corridor". Approved junctions are turned green just before the ambulance arrives, nearby road users get a live warning, and the hospital watches the ETA and the patient's condition until handover.

Project: MCA mini project. Student: R Karthik (25MC201A28), guide A. Mahesh, Anurag University, Department of Information Technology.

---

## What is in the box

| Folder | What it is |
|---|---|
| `backend/` | Spring Boot 3.5 (Java 17+), PostgreSQL, JWT security, WebSocket (STOMP) live updates |
| `frontend/` | React 18 + Vite web app, Leaflet map, one dashboard per role |
| `scripts/e2e_flow.py` | Plays two complete emergencies against a running backend and checks every step |
| `docker-compose.yml` | One-command start (see "Docker" below, **not tested**) |

### Six role dashboards

| Role | What they do |
|---|---|
| Patient | Pin location on the map, choose severity and care type, request an ambulance, watch live tracking, ETAs, junction clearances, cancel |
| Hospital | Edit live bed count and specialities, accept or reject incoming patients, see ETA and paramedic vitals, confirm handover |
| Ambulance (paramedic) | Accept dispatch, start trip (simulated GPS or the device's real GPS), mark patient on board, send heart rate / SpO2 / notes |
| Traffic police | Live map of signals and ambulances, approve or reject green corridors per junction or for the whole trip |
| Road user | Place yourself on the map, get a full-screen warning when an ambulance is approaching, alert history |
| Admin | Analytics (time saved, red-light waits, charts), all trips, users, hospitals, ambulances, signal timings, road congestion, auto-approve setting |

---

## Requirements

- Java 17 or newer (JDK) and Maven 3.9+
- Node.js 18 or newer and npm
- PostgreSQL 14 or newer

## 1. Create the database

Run this on the machine where PostgreSQL is installed, in a terminal:

```bash
sudo -u postgres psql -c "CREATE USER sanet WITH PASSWORD 'sanet123';"
sudo -u postgres psql -c "CREATE DATABASE smart_ambulance OWNER sanet;"
```

On Windows, open "SQL Shell (psql)" as the `postgres` user and run the same two statements without `sudo -u postgres psql -c` and without the surrounding quotes:

```sql
CREATE USER sanet WITH PASSWORD 'sanet123';
CREATE DATABASE smart_ambulance OWNER sanet;
```

Tables are created automatically on first start, and the demo data (hospitals, ambulances, junctions, roads, accounts) is seeded automatically.

## 2. Start the backend

Run in a terminal, inside the `backend` folder:

```bash
cd backend
mvn spring-boot:run
```

Wait for the line `Started SmartAmbulanceApplication`. The API is now on http://localhost:8080.

## 3. Start the web app

Run in a second terminal, inside the `frontend` folder:

```bash
cd frontend
npm install
npm run dev
```

Open http://localhost:5173 in the browser.

## Demo accounts

Every account has the password `Demo@123`. The login page also has one-click buttons for them.

| Role | Email |
|---|---|
| Admin | `admin@sanet.demo` |
| Traffic police | `police@sanet.demo` |
| Patient | `patient@sanet.demo`, `patient2@sanet.demo` |
| Hospital staff | `hospital1@sanet.demo` ... `hospital5@sanet.demo` |
| Ambulance | `ambulance1@sanet.demo` ... `ambulance5@sanet.demo` |
| Road user | `driver1@sanet.demo` ... `driver8@sanet.demo` |

**Sample data.** On first start with an empty database the app also loads 14 completed sample emergencies (spread over five days and all five hospitals, some with green corridors, some without) and one cancelled request, plus their junction clearances and road-user alerts. That is why the Admin analytics, the Trips tab, the patient history, each hospital's history and the road-user alert logs are filled from the start. It only runs when there are no requests yet, and you can turn it off with the environment variable `SEED_SAMPLE_HISTORY=false`.

Public sign-up on the Register page creates only Patient and Road-user accounts. Hospital, ambulance, police and admin accounts are created by an admin (Admin > Users).

---

## Demo walkthrough (about 3 minutes)

Use separate browser windows (or one normal and one private window) so several roles can stay logged in at once.

1. **Road user:** log in as `driver3@sanet.demo` and keep the window open. Make sure "Warn me" is ticked.
2. **Traffic police:** log in as `police@sanet.demo` in another window.
3. **Patient:** log in as `patient@sanet.demo`, click the map near the middle of the road network, choose **Critical** and **Cardiac**, press **Request ambulance now**.
4. **Hospital:** the request goes to the best-ranked hospital, normally `hospital1@sanet.demo` (CityCare). Log in as that account and press **Accept patient**. (If the patient's page says another hospital was offered, use that hospital's account. The ranking table on the patient page shows why.)
5. **Ambulance:** the nearest free ambulance is assigned, for example `ambulance2@sanet.demo`. Log in as it and press **Accept and start (simulated GPS)**.
6. **Police:** press **Approve whole corridor**. Watch the signals on the map: just before the ambulance arrives, each approved junction turns bold green.
7. **Road user:** when the ambulance is within about 900 m ahead on its route, a red "Ambulance approaching" banner appears.
8. After pickup the ambulance heads to the hospital (approve the corridor again for the new leg). The hospital sees the ETA and can confirm **handover** on arrival.
9. **Admin:** `admin@sanet.demo` > Analytics shows time saved by corridors versus time lost at red lights.

To see the difference corridors make, run a second emergency and press **Reject all** (or do nothing): the ambulance now stops at red lights and the "Waited at red" counter grows.

### Simulation speed

The demo compresses time. By default 1 real second is 6 simulated seconds, so a 10-minute drive takes under 2 real minutes. Change it with the `SIM_SPEED` environment variable (see below).

---

## Configuration

Set these environment variables before `mvn spring-boot:run` (Linux/macOS: `export NAME=value`; Windows PowerShell: `$env:NAME="value"`). All are optional.

| Variable | Default | Meaning |
|---|---|---|
| `DB_URL` | `jdbc:postgresql://localhost:5432/smart_ambulance` | Database URL |
| `DB_USER` | `sanet` | Database user |
| `DB_PASSWORD` | `sanet123` | Database password |
| `JWT_SECRET` | a development-only value | **Change this** for anything that is not a classroom demo. At least 32 characters |
| `CORS_ORIGINS` | `http://localhost:5173` | Allowed browser origins, comma separated |
| `SIM_SPEED` | `6` | Simulated seconds per real second. Use `1` for real-time |

Other tunables (hospital ranking weights, green-light lead time of 30 s, alert distance of 900 m, ambulance speed) are in `backend/src/main/resources/application.properties`.

Using the ambulance's **real device GPS** ("Accept using this device's GPS") only makes sense with `SIM_SPEED=1`, because the clock must run at real time. Browsers allow location access only on `localhost` or HTTPS.

## Run the checks

Unit tests (algorithms: haversine, route plan, per-junction ETA, signal cycle, hospital scoring). Run in the `backend` folder:

```bash
cd backend
mvn test
```

End-to-end API test (backend must be running; plays two emergencies, with and without the green corridor, and checks access control and analytics). Run in the project root:

```bash
python3 scripts/e2e_flow.py
```

It leaves its two trips in the database; they show up in the admin analytics.

---

## How it works

**Hospital ranking.** `score = w_travel x severityMultiplier x travelMinutes + w_speciality x (speciality not offered) + w_beds x (1 - freeBeds / totalBeds)`, lowest wins. Defaults: 1.0 / 15 / 10, severity multiplier 0.7 (minor), 1.0 (serious), 1.5 (critical). So a critical patient weighs travel time more. Hospitals with no free beds are skipped. If a hospital rejects or does not answer within 60 s, the next one is tried.

**Routing and ETA.** Junctions and roads form a graph (`RoadNetwork`). Dijkstra finds the fastest route for the ambulance, where road speed is the ambulance's base speed divided by that road's congestion. For every junction ahead, `EtaEngine` predicts the arrival time twice: with the corridor approved (no waiting) and without (it waits for the known red phase). The difference is the time the corridor saves.

**Green corridor.** One clearance request per junction is created when the route is planned. Statuses: `PENDING -> APPROVED -> GREEN_SENT -> PASSED` (or `REJECTED`, `CANCELLED`, `EXPIRED`). When an approved junction is within 30 simulated seconds of the ambulance, its signal is forced green, and released after the ambulance passes (or after 150 s at most as a safety). Without approval the signal keeps its normal cycle.

**Driver alerts.** Opted-in road users whose position is within 900 m ahead of the ambulance's remaining route and within 250 m of it get an alert, with a 25 s cooldown. Alerts are sent over WebSocket and stored in history.

**Live updates.** The server pushes snapshots over STOMP/WebSocket (`/topic/request/{id}`, `/topic/hospital/{id}`, `/topic/ambulance/{id}`, `/topic/police`, `/topic/signals`, `/topic/fleet`, and per-user `/user/queue/notifications` and `/user/queue/alerts`). The WebSocket handshake authenticates the JWT and each subscription is checked against the user's role, so a patient cannot listen to another patient's trip.

**Security.** Passwords are BCrypt-hashed, sessions are stateless JWTs, every endpoint is role-checked on the server (`@PreAuthorize`), and the trip data a user can read is limited to their own trips (patient), their hospital's trips, or their ambulance's trip.

---

## Differences from the report (please read)

The project report describes the full production vision. This build is a working, testable version of it and differs in these ways:

- **No Google Maps / live traffic.** Maps are OpenStreetMap tiles via Leaflet. Travel times come from a built-in road graph of 19 Hyderabad junctions and 26 roads with simulated congestion. `RoadNetwork` is the one class to replace with a real routing/traffic provider.
- **No PostGIS.** Distances are computed in Java (haversine). Plain PostgreSQL is enough.
- **No real SMS or push notifications.** `Broadcaster.sms(...)` writes the message to the log. Replace its body with a Twilio or Firebase call to send real ones. In-app toasts and driver alerts do work, over WebSocket.
- **Signals are simulated.** `SignalService` holds each junction's cycle and the forced-green state. A real deployment would send the "go green" command to the city's signal controller from that class.
- **Everything is demo data.** Junction coordinates are approximate, the five hospitals and five ambulances are fictional, and the phone numbers are fake.
- **Not for real emergencies.** This is a demonstration system. Do not use it in place of 108 / 112.
- Before any real deployment: set a strong `JWT_SECRET`, change the seeded passwords, serve over HTTPS, and put the database behind its own credentials.

## Deploy online (Render, free)

`render.yaml` and the root `Dockerfile` run the whole app as **one service**: the backend also serves the web app, so there is nothing to configure between frontend and backend. Render creates the PostgreSQL database for you.

1. Push the project to a GitHub repository (commands in the chat reply, or `git init`, `git add .`, `git commit`, `git push`).
2. Go to https://dashboard.render.com, click **New > Blueprint**, pick the repository and click **Apply**.
3. Wait for the first build (about 5-8 minutes). Your site is at `https://smart-ambulance-network.onrender.com` (the exact name is shown in the dashboard).

Free-plan limits (from Render's docs): the service sleeps after 15 minutes without traffic and takes about a minute to wake up, and the free database is deleted 30 days after creation unless upgraded. The demo accounts are public on a live site, so share the link only with people you trust, and change the seeded passwords (Admin > Users) before showing it widely.

## Docker (not tested)

`docker-compose.yml` and the two Dockerfiles are included for a one-command start, but Docker could not be run where this project was built, so they have **not been tested**. The compose file itself passes `docker compose config`. If you want to try it, run this in the project root:

```bash
docker compose up --build
```

then open http://localhost:5173. If anything fails, use the manual steps above, which are tested.

## Project layout

```
backend/src/main/java/com/smartambulance/
  config/    security, WebSocket, properties, demo data seeding
  domain/    JPA entities and enums
  repo/      Spring Data repositories
  geo/       haversine, route plan geometry
  sim/       simulation clock, road graph, ETA engine, signals, trip engine, driver alerts
  service/   hospital ranking, clearance, requests, analytics
  web/       REST controllers per role
  ws/        STOMP broadcaster and subscription security
  security/  JWT
frontend/src/
  pages/     one dashboard per role (+ login/register)
  components/ map, trip map, shared UI
  api.js ws.js hooks.js   REST client, STOMP client, live-data hooks
```
