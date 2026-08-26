# Load Balancing 3 Spring Boot Apps — Setup, Industry Comparison & Kubernetes Equivalent

## Part 1: The Approach

Got it — so each of your 3 apps gets its own load-balanced pool of instances, and nginx routes by path to the right pool. That's a natural combination of all three: multiple instances per app (HA/scaling) and the 3 apps still routed separately.

The idea: nginx's `upstream` directive groups multiple backend instances under one name. You point `proxy_pass` at that name instead of a single `host:port`, and nginx load-balances across the instances automatically (round-robin by default).

---

## Part 2: Step-by-Step Setup

### Step 1 — Run multiple instances of each app, on different ports

```bash
# app1 — 2 instances
java -jar app1.jar --server.port=8081
java -jar app1.jar --server.port=8091

# app2 — 2 instances
java -jar app2.jar --server.port=8082
java -jar app2.jar --server.port=8092

# app3 — 2 instances
java -jar app3.jar --server.port=8083
java -jar app3.jar --server.port=8093
```

(Or in Docker: run each app's image twice, publishing to different host ports — e.g. `-p 8081:8080` and `-p 8091:8080` for two app1 containers.)

### Step 2 — nginx config with `upstream` + `proxy_pass`

`/etc/nginx/nginx.conf` — no change from the earlier fix; it just needs to include `sites-enabled`. The `upstream` blocks go in the site file, not here, since `sites-enabled/*` is already included inside `http {}`:

```nginx
events {
}

http {
    include /etc/nginx/mime.types;
    include /etc/nginx/sites-enabled/*;
}
```

`/etc/nginx/sites-available/myapp` — this is where everything goes, the `upstream` pools and the `server` blocks with routing/TLS:

```nginx
upstream app1_pool {
    server localhost:8081;
    server localhost:8091;
}

upstream app2_pool {
    server localhost:8082;
    server localhost:8092;
}

upstream app3_pool {
    server localhost:8083;
    server localhost:8093;
}

server {
    listen 80;
    server_name localhost;
    return 301 https://$host$request_uri;
}

server {
    listen 443 ssl;
    server_name localhost;

    ssl_certificate     /etc/nginx/certs/selfsigned.crt;
    ssl_certificate_key /etc/nginx/certs/selfsigned.key;

    location /app1/ {
        proxy_pass http://app1_pool/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /app2/ {
        proxy_pass http://app2_pool/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /app3/ {
        proxy_pass http://app3_pool/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Each `location` block routes by path — only now `proxy_pass` points at an `upstream` pool name instead of a single instance. nginx picks which instance in the pool handles each request.

### Step 3 — Load balancing strategies (optional, pick one per `upstream`)

Default is **round-robin** — no extra directive needed, requests alternate 8081 → 8091 → 8081...

```nginx
upstream app1_pool {
    least_conn;              # sends to whichever instance has fewer active connections
    server localhost:8081;
    server localhost:8091;
}
```

```nginx
upstream app1_pool {
    ip_hash;                 # same client IP always goes to same instance (session stickiness)
    server localhost:8081;
    server localhost:8091;
}
```

```nginx
upstream app1_pool {
    server localhost:8081 weight=3;   # gets 3x traffic vs the other
    server localhost:8091 weight=1;
}
```

### Step 4 — Health checks (basic, built into open-source nginx)

```nginx
upstream app1_pool {
    server localhost:8081 max_fails=3 fail_timeout=30s;
    server localhost:8091 max_fails=3 fail_timeout=30s;
}
```

If an instance fails 3 times, nginx stops sending it traffic for 30 seconds and retries after.

### Step 5 — Test and reload

```bash
sudo nginx -t
sudo systemctl reload nginx
```

### Step 6 — Verify load balancing is working

Hit the endpoint a few times and check each instance's logs/output — requests should alternate between the two ports for each app:

```bash
for i in {1..6}; do curl -sk https://localhost/app1/api/hello; echo; done
```

---

## Part 3: Is This the Industry-Standard Way?

Short answer: **the concept is standard, but the specific setup above (static `upstream` blocks with hardcoded ports) is the simplified/learning version.**

### What's genuinely standard
- **nginx (or similar) doing reverse-proxy + load balancing** — very common, real companies use exactly this pattern.
- **TLS termination at the edge, HTTP internally** — standard everywhere.
- **Round-robin / least-conn / weighted balancing concepts** — same algorithms used in production tools too.

### Where production setups diverge

**1. Static config → dynamic service discovery**
Hardcoding `server localhost:8081;` etc. doesn't hold up in real deployments where instances come and go constantly (scaling up/down, crashes, deploys). Companies use:
- **Kubernetes + a Service/Ingress** — Kubernetes tracks healthy pod IPs automatically; no manual port lists.
- **Consul, etcd, or cloud service discovery** feeding nginx/Envoy configs dynamically.

**2. Basic health checks → active health checks**
Open-source nginx's `max_fails`/`fail_timeout` is *passive* — it only notices a backend is down after a real request fails. Production setups often use:
- **nginx Plus** (paid) or **HAProxy** — active health checks that proactively probe `/health` endpoints.
- **Kubernetes readiness/liveness probes** — removes unhealthy pods from rotation before nginx ever sees them.

**3. A single nginx instance → the LB itself needs to be highly available**
A single nginx process is a single point of failure. Companies typically run:
- Multiple LB instances behind a **cloud load balancer** (AWS ALB/ELB, GCP Load Balancer) or **keepalived + VRRP** for failover, or
- A managed **API Gateway** (Kong, AWS API Gateway, Spring Cloud Gateway) which often replaces raw nginx for this exact reason.

**4. Manual `java -jar` on ports → container orchestration**
Running instances by hand on incrementing ports (8081, 8091...) doesn't scale operationally. Standard practice is:
- **Docker + Kubernetes/ECS/Nomad** managing instance count, restarts, and rolling deploys.
- Autoscaling based on CPU/traffic, not manually starting more `java -jar` processes.

### Mapping to production equivalents

| What we built | Production equivalent |
|---|---|
| nginx `upstream` blocks | Kubernetes Service + Ingress, or a managed cloud LB |
| Manual `java -jar --server.port=X` | Kubernetes Deployment with N replicas |
| `max_fails`/`fail_timeout` | Readiness/liveness probes |
| Self-signed cert | Cert-manager + Let's Encrypt, or a cert from the cloud provider |
| Single nginx instance | Multiple LB nodes behind a cloud-managed LB, or nginx Ingress Controller |

### Bottom line

The mental model here is the right one — it's exactly what's happening conceptually inside Kubernetes Ingress controllers and cloud load balancers under the hood. For a small deployment (a handful of VMs, not needing autoscaling), plain nginx with `upstream` blocks is genuinely still used in production by many smaller companies and internal tools — it's not a toy. It's the dynamic, self-healing, cloud-native version that larger companies add on top once scale/reliability demands it.

---

## Part 4: The Kubernetes Equivalent

### The mapping, concretely

| nginx/VM world | Kubernetes equivalent |
|---|---|
| Multiple `java -jar` instances on different ports | **Deployment** with `replicas: 2` |
| `upstream app1_pool { server ...; server ...; }` | **Service** (auto-discovers pod IPs, load-balances) |
| `location /app1/ { proxy_pass ...; }` routing | **Ingress** rule for path `/app1` |
| Self-signed cert in nginx | **cert-manager** + Let's Encrypt, or a **Secret** holding your cert |
| `max_fails`/`fail_timeout` | **readinessProbe** / **livenessProbe** |
| Single nginx process | **Ingress Controller** (itself often nginx, but managed + HA by K8s) |

### 1. Deployment for app1 (replaces manually running 2 `java -jar` instances)

```yaml
# app1-deployment.yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: app1
spec:
  replicas: 2                    # <-- replaces your 2 manual instances (8081, 8091)
  selector:
    matchLabels:
      app: app1
  template:
    metadata:
      labels:
        app: app1
    spec:
      containers:
        - name: app1
          image: my-registry/app1:latest
          ports:
            - containerPort: 8080
          readinessProbe:        # <-- replaces max_fails/fail_timeout, but proactive
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 5
            periodSeconds: 10
          livenessProbe:
            httpGet:
              path: /actuator/health
              port: 8080
            initialDelaySeconds: 15
            periodSeconds: 20
```

`replicas: 2` is the whole reason you don't need `upstream { server localhost:8081; server localhost:8091; }` — Kubernetes just runs however many pods you ask for, and the Service below automatically load-balances across all of them, including future ones if you scale up.

The `readinessProbe` is the real upgrade over nginx's passive health checking — a pod that fails this check is removed from the load-balancing pool before it ever receives real traffic, not after a request fails against it.

### 2. Service for app1 (replaces the `upstream app1_pool` block)

```yaml
# app1-service.yaml
apiVersion: v1
kind: Service
metadata:
  name: app1-service
spec:
  selector:
    app: app1              # <-- matches the Deployment's pod label; auto-discovers all matching pods
  ports:
    - port: 80
      targetPort: 8080
```

There's no list of IPs/ports anywhere — the Service continuously watches for pods labeled `app: app1` and load-balances (round-robin by default) across whichever ones currently exist and are ready. No hardcoded `localhost:8081` list to maintain.

### 3. Repeat for app2 and app3

Same two files, just swap `app1` → `app2`/`app3` and the image name. In practice you'd template this with Helm or Kustomize rather than copy-pasting, but conceptually it's identical.

### 4. Ingress (replaces the entire nginx.conf routing + TLS block)

```yaml
# ingress.yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: myapp-ingress
  annotations:
    nginx.ingress.kubernetes.io/rewrite-target: /$2   # strips /app1 prefix, like the trailing-slash trick
    cert-manager.io/cluster-issuer: letsencrypt-prod   # auto-provisions & renews the cert
spec:
  tls:
    - hosts:
        - myapp.example.com
      secretName: myapp-tls          # cert-manager writes the cert here automatically
  rules:
    - host: myapp.example.com
      http:
        paths:
          - path: /app1(/|$)(.*)
            pathType: ImplementationSpecific
            backend:
              service:
                name: app1-service
                port:
                  number: 80
          - path: /app2(/|$)(.*)
            pathType: ImplementationSpecific
            backend:
              service:
                name: app2-service
                port:
                  number: 80
          - path: /app3(/|$)(.*)
            pathType: ImplementationSpecific
            backend:
              service:
                name: app3-service
                port:
                  number: 80
```

This single file does everything the nginx.conf + sites-available/myapp + manual certbot did:
- Path-based routing (`/app1`, `/app2`, `/app3`) → same idea as the `location` blocks
- `rewrite-target` → same idea as the trailing-slash prefix-stripping trick
- `cert-manager.io/cluster-issuer` → automatically requests and renews a real, trusted Let's Encrypt cert (no more self-signed browser warnings) — replaces the manual `openssl req` step entirely
- `tls:` section → replaces `ssl_certificate`/`ssl_certificate_key` lines

**Important:** this requires an **Ingress Controller** already running in the cluster (commonly `ingress-nginx` — yes, it's nginx under the hood, just managed by Kubernetes with HA and auto-reload built in) and **cert-manager** installed for the automatic TLS part.

### 5. Apply it

```bash
kubectl apply -f app1-deployment.yaml -f app1-service.yaml
kubectl apply -f app2-deployment.yaml -f app2-service.yaml
kubectl apply -f app3-deployment.yaml -f app3-service.yaml
kubectl apply -f ingress.yaml
```

### 6. Verify

```bash
kubectl get pods                # should show 2 pods per app, STATUS: Running
kubectl get svc                 # shows app1-service, app2-service, app3-service
kubectl get ingress             # shows the external address once provisioned
```

### What you get "for free" vs. the nginx/VM setup

- **Self-healing**: a crashed pod is automatically restarted and re-added to the Service's pool.
- **Rolling deploys**: `kubectl set image deployment/app1 app1=my-registry/app1:v2` replaces pods one at a time with zero downtime — no manual "start new instance, stop old one" juggling.
- **Autoscaling** (optional add-on): a `HorizontalPodAutoscaler` can bump `replicas` up/down based on CPU or request load automatically.
- **Trusted certs, auto-renewed**: no more `curl -k` or browser warnings.

### What stays conceptually identical

- Reverse proxy + TLS termination at the edge → still true (the Ingress Controller)
- Load balancing across multiple backend instances → still true (the Service)
- Path-based routing to different apps → still true (the Ingress rules)
- Health checks removing bad instances from rotation → still true, just proactive instead of reactive
