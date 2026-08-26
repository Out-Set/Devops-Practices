# nginx Setup WITHOUT HTTPS (Plain HTTP + Load Balancing)

Without HTTPS, it's simpler — skip the cert, the SSL directives, and the HTTP→HTTPS redirect entirely. nginx just proxies and load-balances over plain HTTP.

## `/etc/nginx/nginx.conf`

No change from the HTTPS setup:

```nginx
events {
}

http {
    include /etc/nginx/mime.types;
    include /etc/nginx/sites-enabled/*;
}
```

## `/etc/nginx/sites-available/myapp`

```bash
sudo nano /etc/nginx/sites-available/myapp
```

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

    location /app1/ {
        proxy_pass http://app1_pool/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    location /app2/ {
        proxy_pass http://app2_pool/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }

    location /app3/ {
        proxy_pass http://app3_pool/;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    }
}
```

## What changed vs. the HTTPS version

| HTTPS version | Plain HTTP version |
|---|---|
| Two `server` blocks (80 → redirect, 443 → actual traffic) | One `server` block, listens on 80 only |
| `listen 443 ssl;` | `listen 80;` |
| `ssl_certificate` / `ssl_certificate_key` lines | Removed entirely |
| `return 301 https://$host$request_uri;` redirect | Removed entirely |
| `proxy_set_header X-Forwarded-Proto $scheme;` | Removed (no longer meaningful without TLS) |
| Needed a cert (`openssl req` / Let's Encrypt) | No cert needed at all |

The `upstream` blocks and load-balancing behavior (round-robin, `least_conn`, `weight`, `max_fails`, etc.) work identically — load balancing has nothing to do with HTTPS, it's a separate concern.

## Apply

```bash
sudo nginx -t
sudo systemctl reload nginx
```

## Test

```bash
curl http://localhost/app1/api/hello
```

No `-k` flag needed this time since there's no TLS to skip verification on.

## Worth knowing

Running production traffic over plain HTTP isn't recommended if this is ever exposed beyond `localhost`/your local network — credentials, cookies, and data travel unencrypted. This is fine for local dev/testing, but for anything reachable over the internet, HTTPS (even with a free Let's Encrypt cert) is the standard expectation today, including from browsers, which flag plain HTTP sites as "Not Secure."
