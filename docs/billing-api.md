# Wisper Commercial & Billing REST API v1

Integration guide for reseller platforms, billing engines, and coin-based hosting dashboards.

---

## 1. Overview & Architecture

Wisper is designed for seamless integration with commercial dashboards. When a customer purchases a server, bot, or application container using coins or fiat currency, your billing platform orchestrates the service lifecycle through Wisper's Commercial REST API.

```mermaid
sequenceDiagram
    autonumber
    actor Customer
    participant Dashboard as Billing Dashboard
    participant Wisper as Wisper Panel
    participant Node as Node Daemon (sasayaki)

    Customer->>Dashboard: 1. Purchase server with coins
    Dashboard->>Wisper: 2. POST /api/v1/commercial/provision
    Note over Wisper: Auto-creates Account, Org, Project,<br/>Service, Volume, and Starts Container
    Wisper->>Node: Pushes desired state spec (RUNNING)
    Wisper-->>Dashboard: Returns serviceId, credentials, direct URLs
    Dashboard-->>Customer: 3. "Ready! Click here to open server"
    Customer->>Wisper: 4. Opens Terminal & File Manager directly

    loop Every Hour (Billing Cron)
        Dashboard->>Wisper: 5. GET /api/v1/commercial/services/{id}
        Wisper-->>Dashboard: Status (RUNNING/STOPPED), CPU, RAM, Disk
        Dashboard->>Dashboard: Deducts coins from customer balance
        alt Balance reaches 0
            Dashboard->>Wisper: 6. POST /api/v1/commercial/services/{id}/stop
            Wisper->>Node: Pauses container (CPU/RAM freed, volume 100% preserved)
        else Customer deposits coins
            Dashboard->>Wisper: 7. POST /api/v1/commercial/services/{id}/start
            Wisper->>Node: Resumes container immediately
        else Grace period expired (e.g., 7 days)
            Dashboard->>Wisper: 8. DELETE /api/v1/commercial/services/{id}
            Wisper->>Node: Purges container and frees resources
        end
    end
```

### Key Principles

1. **Zero-Friction Fast Provisioning**:
   Customers do not need to manually configure Organizations or Projects. One API call orchestrates Account $\rightarrow$ Organization $\rightarrow$ Project $\rightarrow$ Service $\rightarrow$ Persistent Storage $\rightarrow$ Running Container.
2. **Safe Auto-Pause on Balance Depletion**:
   When a customer runs out of coins, your dashboard issues a `/stop` command. The node daemon immediately pauses the container, releasing CPU and memory allocations, while **100% of volume files and databases remain safe on disk**.
3. **Instant Resume on Deposit**:
   When the user tops up coins, issuing `/start` immediately resumes the workload without reinstallation or data loss.
4. **Crash-Free App Defaults**:
   Containers provisioned without a custom command default to keep-alive sandbox mode (`sleep infinity` in `/app` with persistent volume mounted at `/app`), allowing users to immediately upload files or test in terminal without container crash-loops.

---

## 2. Authentication & Security

All requests to `/api/v1/commercial/**` require a bearer token in the `Authorization` header:

```http
Authorization: Bearer wsp_0123456789abcdef...
Content-Type: application/json
```

### Obtaining an API Token
1. Sign in as an administrator on the Wisper Panel.
2. Navigate to **Settings $\rightarrow$ API Tokens** (`/settings/tokens`).
3. Click **Create API Token**, enter a name (e.g. `billing-dashboard`), and select:
   - `services:read`
   - `services:write`
   - `projects:read`
   - `projects:write`
4. Copy the generated token (`wsp_...`). It is shown only once upon creation.

---

## 3. API Reference

### 3.1 Fast Provisioning (1-Click Buy)

Creates an account (if not already existing), resolves the tenant organization and default project, configures the service with CPU/RAM/Disk, attaches persistent volume storage, and starts the container.

- **Endpoint**: `POST /api/v1/commercial/provision`
- **Alias**: `POST /api/v1/commercial/orders`

#### Request Payload

| Field | Type | Required | Description |
|---|---|---|---|
| `email` | `string` | **Yes** | Customer email address. |
| `displayName` | `string` | No | Customer display name (defaults to email prefix). |
| `password` | `string` | No | Optional initial password. If blank, a secure password (`wsp_...`) is generated. |
| `serviceName` | `string` | **Yes** | Name of the server/bot (e.g. `"discord-music-bot"`). |
| `serviceSlug` | `string` | No | URL address slug (auto-derived from name if omitted). |
| `kind` | `string` | No | `"APP"` (default) or `"SITE"`. |
| `image` | `string` | No | Docker image (default: `"python:3.12-slim"`). E.g. `"node:22-alpine"`, `"ubuntu:24.04"`. |
| `command` | `string` | No | Startup command. If omitted for APP, defaults to `"sleep infinity"` sandbox mode. |
| `workingDir` | `string` | No | Working directory (default: `"/app"`). |
| `port` | `integer` | No | Exposed container port (e.g. `8000`, `3000`). |
| `cpuMillicores` | `integer` | No | CPU limit (default: `500` = 0.5 vCPU; `1000` = 1 vCPU). |
| `memoryBytes` | `integer` | No | RAM limit in bytes (default: `268435456` = 256 MiB; `1073741824` = 1 GiB). |
| `attachVolume` | `boolean` | No | Attach persistent disk storage (default: `true` for APP). |
| `volumeName` | `string` | No | Name of volume (default: `"data"`). |
| `volumeMountPath`| `string` | No | Mount path in container (default: matches `workingDir` or `"/app"`). |
| `volumeSizeBytes`| `integer` | No | Storage quota in bytes (default: `5368709120` = 5 GiB). |
| `environment` | `object` | No | Key-value dictionary of initial environment variables. |
| `autoStart` | `boolean` | No | Whether to start container immediately (default: `true`). |

#### Example Request (`cURL`)

```bash
curl -X POST "https://panel.example.com/api/v1/commercial/provision" \
  -H "Authorization: Bearer wsp_your_admin_token_here" \
  -H "Content-Type: application/json" \
  -d '{
    "email": "customer@example.com",
    "displayName": "Alex Nguyen",
    "serviceName": "python-bot",
    "image": "python:3.12-slim",
    "cpuMillicores": 1000,
    "memoryBytes": 1073741824,
    "attachVolume": true,
    "volumeMountPath": "/app",
    "volumeSizeBytes": 5368709120,
    "autoStart": true,
    "environment": {
      "BOT_ENV": "production"
    }
  }'
```

#### Example Response (`201 Created`)

```json
{
  "serviceId": "550e8400-e29b-41d4-a716-446655440000",
  "serviceName": "python-bot",
  "serviceSlug": "python-bot",
  "kind": "APP",
  "status": "RUNNING",
  "projectId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "organizationId": "6ba7b811-9dad-11d1-80b4-00c04fd430c8",
  "accountId": "6ba7b812-9dad-11d1-80b4-00c04fd430c8",
  "accountEmail": "customer@example.com",
  "isNewAccount": true,
  "initialPassword": "wsp_K8d!xP3mN9vB4q",
  "cpuMillicores": 1000,
  "memoryBytes": 1073741824,
  "diskBytes": 5368709120,
  "image": "python:3.12-slim",
  "containerPort": null,
  "urls": {
    "panel": "/services/550e8400-e29b-41d4-a716-446655440000",
    "terminal": "/services/550e8400-e29b-41d4-a716-446655440000/terminal",
    "files": "/services/550e8400-e29b-41d4-a716-446655440000/files",
    "metrics": "/services/550e8400-e29b-41d4-a716-446655440000/metrics"
  }
}
```

---

### 3.2 Get Server Status & Specs (Hourly Metering)

Queries the exact real-time operational status, CPU, memory, total disk storage, and uptime. Billing crons use this endpoint to compute coin deductions.

- **Endpoint**: `GET /api/v1/commercial/services/{serviceId}`

#### Example Request

```bash
curl -X GET "https://panel.example.com/api/v1/commercial/services/550e8400-e29b-41d4-a716-446655440000" \
  -H "Authorization: Bearer wsp_your_admin_token_here"
```

#### Example Response (`200 OK`)

```json
{
  "serviceId": "550e8400-e29b-41d4-a716-446655440000",
  "name": "python-bot",
  "slug": "python-bot",
  "kind": "APP",
  "status": "RUNNING",
  "desiredState": "RUNNING",
  "reportedState": "RUNNING",
  "health": "HEALTHY",
  "cpuMillicores": 1000,
  "memoryBytes": 1073741824,
  "diskBytes": 5368709120,
  "volumes": [
    {
      "id": "7ca7b810-9dad-11d1-80b4-00c04fd430c8",
      "name": "data",
      "mountPath": "/app",
      "sizeBytes": 5368709120
    }
  ],
  "containerPort": null,
  "image": "python:3.12-slim",
  "nodeId": "8da7b810-9dad-11d1-80b4-00c04fd430c8",
  "uptimeSeconds": 7240,
  "createdAt": "2026-09-19T08:30:00Z",
  "updatedAt": "2026-09-19T08:30:00Z",
  "organizationId": "6ba7b811-9dad-11d1-80b4-00c04fd430c8",
  "projectId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "urls": {
    "panel": "/services/550e8400-e29b-41d4-a716-446655440000",
    "terminal": "/services/550e8400-e29b-41d4-a716-446655440000/terminal",
    "files": "/services/550e8400-e29b-41d4-a716-446655440000/files",
    "metrics": "/services/550e8400-e29b-41d4-a716-446655440000/metrics"
  }
}
```

---

### 3.3 Stop Server (Depleted Coins Warning)

Halts the container on the node. Frees up CPU and RAM on the node immediately.
**Persistent disk volume data is 100% preserved.**

- **Endpoint**: `POST /api/v1/commercial/services/{serviceId}/stop`

#### Example Request

```bash
curl -X POST "https://panel.example.com/api/v1/commercial/services/550e8400-e29b-41d4-a716-446655440000/stop" \
  -H "Authorization: Bearer wsp_your_admin_token_here"
```

#### Example Response (`200 OK`)

```json
{
  "status": "STOPPED",
  "message": "Service stopped. Volumes preserved."
}
```

---

### 3.4 Start Server (Coin Deposit / Resume)

Resumes a stopped container on the node daemon. All persistent volume files and databases are intact.

- **Endpoint**: `POST /api/v1/commercial/services/{serviceId}/start`

#### Example Request

```bash
curl -X POST "https://panel.example.com/api/v1/commercial/services/550e8400-e29b-41d4-a716-446655440000/start" \
  -H "Authorization: Bearer wsp_your_admin_token_here"
```

#### Example Response (`200 OK`)

```json
{
  "status": "RUNNING",
  "message": "Service started successfully."
}
```

---

### 3.5 Restart Server

Restarts the workload container on the node.

- **Endpoint**: `POST /api/v1/commercial/services/{serviceId}/restart`

#### Example Response (`200 OK`)

```json
{
  "status": "RUNNING",
  "message": "Service restart initiated."
}
```

---

### 3.6 Permanently Delete Server (Eviction)

Releases node placement, deletes the service record, and publishes container removal to the node daemon. Called when a customer has not topped up coins after the retention window (e.g. 7 days).

- **Endpoint**: `DELETE /api/v1/commercial/services/{serviceId}`

#### Example Response (`200 OK`)

```json
{
  "deleted": true,
  "message": "Service permanently deleted."
}
```

---

### 3.7 List Customer Servers

Lists all servers belonging to an account.

- **Endpoint**: `GET /api/v1/commercial/services?email=customer@example.com`
- **Or**: `GET /api/v1/commercial/services?accountId=...`

#### Example Response (`200 OK`)

```json
[
  {
    "serviceId": "550e8400-e29b-41d4-a716-446655440000",
    "name": "python-bot",
    "status": "RUNNING",
    "cpuMillicores": 1000,
    "memoryBytes": 1073741824,
    "diskBytes": 5368709120,
    "urls": {
      "panel": "/services/550e8400-e29b-41d4-a716-446655440000"
    }
  }
]
```

---

### 3.8 Account Suspension & Reactivation

Blocks or unblocks customer login across the panel.

- **Suspend**: `POST /api/v1/commercial/accounts/{accountId}/suspend`
- **Reactivate**: `POST /api/v1/commercial/accounts/{accountId}/reactivate`

---

## 4. Hourly Coin Billing Implementation

Here are reference scripts demonstrating how a billing dashboard runs an hourly cron to check status, calculate coin charges, and enforce auto-stop when coins run out.

### Python Example (`hourly_billing.py`)

```python
import os
import requests

PANEL_URL = os.getenv("WISPER_PANEL_URL", "https://panel.example.com")
API_TOKEN = os.getenv("WISPER_API_TOKEN")

HEADERS = {
    "Authorization": f"Bearer {API_TOKEN}",
    "Content-Type": "application/json"
}

# Example Coin Pricing Matrix per hour
# 1000 millicores (1 vCPU) = 10 coins/hr
# 1 GiB RAM = 5 coins/hr
# 10 GiB Disk = 2 coins/hr
def calculate_hourly_coins(service_info):
    if service_info.get("status") != "RUNNING":
        # Stopped servers only consume disk storage, not CPU/RAM
        disk_gib = service_info.get("diskBytes", 0) / (1024 ** 3)
        return max(1, int(disk_gib * 0.2))

    cpu_cores = service_info.get("cpuMillicores", 500) / 1000.0
    ram_gib = service_info.get("memoryBytes", 268435456) / (1024 ** 3)
    disk_gib = service_info.get("diskBytes", 5368709120) / (1024 ** 3)

    cost = (cpu_cores * 10) + (ram_gib * 5) + (disk_gib * 0.2)
    return max(1, round(cost))

def process_service_billing(service_id, customer_coin_balance, deduct_coins_callback):
    url = f"{PANEL_URL}/api/v1/commercial/services/{service_id}"
    res = requests.get(url, headers=HEADERS, timeout=10)
    res.raise_for_status()
    service = res.json()

    cost = calculate_hourly_coins(service)
    print(f"Service {service['name']} ({service['status']}) costs {cost} coins/hr")

    if customer_coin_balance >= cost:
        deduct_coins_callback(cost)
        print(f"Deducted {cost} coins. Remaining balance: {customer_coin_balance - cost}")
    else:
        print(f"Insufficient coins! (Balance: {customer_coin_balance} < Cost: {cost})")
        # Send warning stop to pause container and save CPU/RAM
        stop_url = f"{PANEL_URL}/api/v1/commercial/services/{service_id}/stop"
        stop_res = requests.post(stop_url, headers=HEADERS, timeout=10)
        if stop_res.status_code == 200:
            print("Successfully paused server. Files are preserved.")
```

### Node.js Example (`hourlyBilling.js`)

```javascript
import fetch from 'node-fetch';

const PANEL_URL = process.env.WISPER_PANEL_URL || 'https://panel.example.com';
const API_TOKEN = process.env.WISPER_API_TOKEN;

const headers = {
  Authorization: `Bearer ${API_TOKEN}`,
  'Content-Type': 'application/json',
};

async function billHourlyService(serviceId, userBalance, chargeUserFn) {
  const resp = await fetch(`${PANEL_URL}/api/v1/commercial/services/${serviceId}`, { headers });
  if (!resp.ok) throw new Error(`Failed to query service: ${resp.status}`);
  const service = await resp.json();

  // If running, charge CPU + RAM + Storage. If stopped, charge only Storage.
  let hourlyCoins = 1;
  if (service.status === 'RUNNING') {
    const cpuCores = service.cpuMillicores / 1000;
    const ramGib = service.memoryBytes / (1024 * 1024 * 1024);
    hourlyCoins = Math.ceil(cpuCores * 10 + ramGib * 5);
  }

  if (userBalance >= hourlyCoins) {
    await chargeUserFn(hourlyCoins);
    console.log(`Charged ${hourlyCoins} coins for ${service.name}.`);
  } else {
    console.warn(`User out of coins. Stopping ${service.name} to preserve quota.`);
    await fetch(`${PANEL_URL}/api/v1/commercial/services/${serviceId}/stop`, {
      method: 'POST',
      headers,
    });
  }
}
```

---

## 5. HTTP Response Code Reference

| Status | Code | Meaning |
|---|---|---|
| `200 OK` | `OK` | The operation succeeded (status retrieved, container started/stopped/deleted). |
| `201 Created` | `CREATED` | Server provisioned successfully with initial credentials and URLs. |
| `400 Bad Request` | `VALIDATION_ERROR` | Missing required fields, invalid email format, or incompatible resource limits. |
| `401 Unauthorized`| `UNAUTHORIZED` | API token missing, invalid, or expired. |
| `403 Forbidden` | `FORBIDDEN` | API token lacks required scope or permissions. |
| `404 Not Found` | `NOT_FOUND` | Specified service, project, or account ID does not exist. |
| `409 Conflict` | `CONFLICT` | Service or address slug already in use. |
