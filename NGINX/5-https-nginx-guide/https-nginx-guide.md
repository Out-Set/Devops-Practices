# Running Spring Boot Apps on HTTPS via nginx

Covers three scenarios:
1. With Docker Compose
2. Without Docker Compose (plain `docker run`)
3. Serving 3 Spring Boot apps behind one nginx (path-based routing)

**Core idea in all three:** nginx holds the SSL certificate and terminates HTTPS. Your Spring Boot app(s) only ever speak plain HTTP internally, on ports like 8081/8082/8083. Nothing except nginx is exposed on 443.

---

## 0. Generate a self-signed certificate (needed for local/testing HTTPS)

A self-signed cert works for local testing but will show a browser warning ("not secure") since it isn't issued by a trusted authority. That's expected — click through it, or use `curl -k` to skip verification.

```bash
sudo mkdir -p /etc/nginx/certs
sudo openssl req -x509 -nodes -days 365 -newkey rsa:2048 \
  -keyout /etc/nginx/certs/selfsigned.key \
  -out /etc/nginx/certs/selfsigned.crt \
  -subj "/CN=localhost"
```

This creates:
- `/etc/nginx/certs/selfsigned.crt` — the certificate
- `/etc/nginx/certs/selfsigned.key` — the private key

(For a real deployment with a public domain, replace this with a Let's Encrypt cert via Certbot instead.)

---

## 1. Setup WITH Docker Compose

Use this when both your Spring Boot app(s) and nginx run as containers.

### 1.1 Project structure

```
my-project/
├── docker-compose.yml
├── nginx/
│   ├── nginx.conf
│   └── certs/
│       ├── selfsigned.crt
│       └── selfsigned.key
└── my-spring-app/
    ├── Dockerfile
    └── (your spring boot app files)
```

### 1.2 Spring Boot `Dockerfile` (plain HTTP, no SSL config needed)

```dockerfile
FROM eclipse-temurin:21-jre
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

`application.properties` stays simple:
```properties
server.port=8080
```

### 1.3 `nginx/nginx.conf`

```nginx
events {}

http {
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

        location / {
            proxy_pass http://spring-app:8080;
            proxy_set_header Host $host;
            proxy_set_header X-Real-IP $remote_addr;
            proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
            proxy_set_header X-Forwarded-Proto $scheme;
        }
    }
}
```

Note: `proxy_pass` uses the **container name** `spring-app` — Docker Compose gives each service a DNS name matching its service key, resolvable by other containers on the same network.

### 1.4 `docker-compose.yml`

```yaml
services:
  spring-app:
    build: ./my-spring-app
    expose:
      - "8080"          # internal only, not exposed to host

  nginx:
    image: nginx:latest
    ports:
      - "80:80"
      - "443:443"       # only nginx is exposed to your machine
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/nginx.conf:ro
      - ./nginx/certs:/etc/nginx/certs:ro
    depends_on:
      - spring-app
```

### 1.5 Run

```bash
docker compose up --build
```

### 1.6 Test

```bash
curl -k https://localhost/your-endpoint
```

---

## 2. Setup WITHOUT Docker Compose

Two variants: (a) nginx also in Docker, run manually, or (b) nginx installed directly on the host (e.g. via `apt install nginx`). Variant (b) is what we ended up using and debugging — documented in detail below.

### 2a. Manual `docker run` (nginx also in a container)

```bash
# 1. Create a shared network (Compose does this automatically; here it's manual)
docker network create my-network

# 2. Build and run Spring Boot app — no -p flag, internal only
cd my-spring-app
docker build -t my-spring-app .
docker run -d --name spring-app --network my-network my-spring-app

# 3. Run nginx on the same network, only nginx is published to host
docker run -d \
  --name nginx-proxy \
  --network my-network \
  -p 80:80 -p 443:443 \
  -v $(pwd)/nginx/nginx.conf:/etc/nginx/nginx.conf:ro \
  -v $(pwd)/nginx/certs:/etc/nginx/certs:ro \
  nginx:latest
```

`nginx.conf` stays identical to section 1.3 — `proxy_pass http://spring-app:8080;` still resolves via Docker's DNS as long as both containers share `my-network`.

Management:
```bash
docker stop spring-app nginx-proxy
docker start spring-app nginx-proxy
docker rm -f spring-app nginx-proxy
docker network rm my-network
```

### 2b. nginx installed directly on the host (e.g. `sudo apt install nginx`)

This is the path we walked through and debugged step by step. Full checklist below.

#### Step 1 — Locate nginx's config layout (Ubuntu/apt install)

```
/etc/nginx/nginx.conf              → main config
/etc/nginx/sites-available/        → put your site configs here
/etc/nginx/sites-enabled/          → symlinks to enabled configs
/var/log/nginx/                    → access.log, error.log
```

#### Step 2 — Generate the cert (see section 0)

#### Step 3 — Run the Spring Boot app on plain HTTP

```bash
java -jar app.jar --server.port=8080
```
or, if containerized (published to host since nginx is NOT in Docker this time):
```bash
docker run -d -p 8080:8080 my-spring-app
```

#### Step 4 — Create the site config

```bash
sudo nano /etc/nginx/sites-available/myapp
```

```nginx
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

    location / {
        proxy_pass http://localhost:8080;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

Save: `Ctrl+O`, `Enter`. Exit: `Ctrl+X`.

`proxy_pass` now points to `http://localhost:8080` — since nginx isn't in Docker, it just talks to `localhost` like any other host process.

#### Step 5 — Enable the site

```bash
sudo ln -s /etc/nginx/sites-available/myapp /etc/nginx/sites-enabled/
sudo rm -f /etc/nginx/sites-enabled/default   # avoid conflicts on 80/443
```

#### Step 6 — ⚠️ CRITICAL: make sure `nginx.conf` actually loads `sites-enabled`

This was the bug we hit. A default `nginx.conf` from some installs looks like this — **it has no `include` for `sites-enabled` at all**, and instead hardcodes its own server block:

```nginx
# BROKEN — do not use
events {}
http {
    include /etc/nginx/mime.types;
    server {
        listen 80;
        server_name _;
        root /etc/nginx/website;
    }
}
```

With this file, anything you put in `sites-available`/`sites-enabled` is silently ignored — nginx never reads it. Symptoms:
- `curl https://localhost/...` fails with "couldn't connect" (nothing listens on 443)
- error log shows nginx trying to serve requests as **static files** from a `root` path, e.g.:
  ```
  open() "/etc/nginx/website/app1/api/hello" failed (2: No such file or directory)
  ```

**Fix** — edit `/etc/nginx/nginx.conf`:

```bash
sudo nano /etc/nginx/nginx.conf
```

Replace the entire contents with:

```nginx
events {
}

http {
    include /etc/nginx/mime.types;
    include /etc/nginx/sites-enabled/*;
}
```

Save and exit.

#### Step 7 — Test and reload

```bash
sudo nginx -t
```
Expect:
```
nginx: configuration file /etc/nginx/nginx.conf test is successful
```

```bash
sudo systemctl reload nginx
```

If nginx wasn't running at all, `reload` won't start it — use:
```bash
sudo systemctl start nginx
sudo systemctl enable nginx    # optional: auto-start on boot
```

#### Step 8 — Verify nginx is actually listening on 443

```bash
sudo ss -tlnp | grep -E '80|443'
```
You should see nginx bound to both `0.0.0.0:80` and `0.0.0.0:443` (or `*:80` / `*:443`). If this is empty, nginx is not actually running/bound — recheck `systemctl status nginx`.

#### Step 9 — Test end to end

```bash
curl -k https://localhost/your-endpoint
```
or open `https://localhost` in a browser (click through the self-signed cert warning).

#### Useful commands for ongoing debugging

```bash
sudo systemctl status nginx                    # is it running?
sudo tail -f /var/log/nginx/error.log           # live errors
sudo nginx -t && sudo systemctl reload nginx    # after any config change
sudo journalctl -xeu nginx.service --no-pager | tail -40   # startup failures
```

---

## 3. Serving 3 Spring Boot apps behind one nginx

Applies to any of the setups above — Docker Compose, manual Docker, or host-installed nginx. Shown here for host-installed nginx (section 2b), since path-based routing needs no `/etc/hosts` changes and works cleanly with just `localhost`.

### 3.1 Run each app on its own port

```bash
java -jar app1.jar --server.port=8081
java -jar app2.jar --server.port=8082
java -jar app3.jar --server.port=8083
```

(Or containerized: `docker run -d -p 8081:8080 app1`, etc.)

### 3.2 Path-based routing config

`/etc/nginx/sites-available/myapp`:

```nginx
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
        proxy_pass http://localhost:8081/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /app2/ {
        proxy_pass http://localhost:8082/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }

    location /app3/ {
        proxy_pass http://localhost:8083/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
```

**Trailing slash matters.** Both `location /app1/` and `proxy_pass http://localhost:8081/` end in `/`. This makes nginx strip the `/app1` prefix before forwarding, so app1 receives requests as if `/app1` was never there — no code changes needed in Spring Boot.

If you instead want the app to receive the full path (e.g. it uses `server.servlet.context-path=/app1`), drop the trailing slash on `proxy_pass`:
```nginx
location /app1/ {
    proxy_pass http://localhost:8081;   # no trailing slash — forwards full path
}
```

### 3.3 Access

```
https://localhost/app1/...
https://localhost/app2/...
https://localhost/app3/...
```

### 3.4 (Alternative) Subdomain-based routing

Only worth it locally if you're simulating a real multi-domain setup. Requires fake hostnames since there's no real DNS:

```bash
# /etc/hosts
127.0.0.1 app1.local
127.0.0.1 app2.local
127.0.0.1 app3.local
```

Then one `server {}` block per app, each with its own `server_name` and identical `ssl_certificate` lines (same self-signed cert covers all, since it was issued for `CN=localhost` — for real subdomains you'd need a cert covering those names, e.g. via SAN or wildcard):

```nginx
server {
    listen 443 ssl;
    server_name app1.local;
    ssl_certificate     /etc/nginx/certs/selfsigned.crt;
    ssl_certificate_key /etc/nginx/certs/selfsigned.key;
    location / {
        proxy_pass http://localhost:8081;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
    }
}
# repeat for app2.local -> 8082, app3.local -> 8083
```

Access: `https://app1.local`, `https://app2.local`, `https://app3.local`.

### 3.5 Apply and test

```bash
sudo nginx -t && sudo systemctl reload nginx

curl -k https://localhost/app1/your-endpoint
curl -k https://localhost/app2/your-endpoint
curl -k https://localhost/app3/your-endpoint
```

---

## Quick reference: comparison of approaches

| | Docker Compose | Plain `docker run` | Host-installed nginx |
|---|---|---|---|
| Orchestration | Automatic (network, DNS) | Manual network creation | N/A — just processes |
| `proxy_pass` target | container name (`spring-app`) | container name (`spring-app`) | `localhost:PORT` |
| Best for | Reproducible multi-service deploys | One-off / learning Docker internals | Quick local testing, no Docker overhead |

## Common pitfalls encountered

1. **`nginx.conf` not including `sites-enabled`** — some default installs hardcode a `server {}` block directly in `nginx.conf` instead of including `sites-enabled/*`. Any site config you create is silently ignored until you add the `include` line. Symptom: `curl` fails to connect on 443, or error log shows attempts to serve static files from an unrelated `root` path.
2. **Missing trailing slash on `proxy_pass`** when path-prefix stripping is expected — causes the app to receive `/app1/...` instead of `/...`, leading to 404s unless `context-path` is set to match.
3. **`reload` vs `start`** — `systemctl reload nginx` only re-reads config for an *already running* nginx. If nginx was never started, `reload` does nothing silently; use `systemctl start nginx` first.
4. **Self-signed cert browser warnings** — expected for local dev; use `curl -k` or click through the browser warning. Not suitable for production — use a CA-issued cert (e.g. Let's Encrypt) there instead.
