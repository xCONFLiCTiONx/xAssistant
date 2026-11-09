# xAssistant server deployment

This deployment installs only the xAssistant reminder API. It does not alter
Ollama models, Cloudflare, UFW, or existing xServer services.

## Target

- API: `http://192.168.88.50:5081`
- Ollama: `http://192.168.88.50:11434`
- Model: `qwen3.5:4b-q4_K_M`
- Timezone: `America/Chicago`
- Persistent database: `/var/lib/xassistant/xassistant.sqlite3`
- Service: `xassistant-api.service`

The API currently has no client authentication. Keep it restricted to the trusted
LAN. Do not publish port 5081 through Cloudflare or expose it to the internet yet.

## Install on xServer

From the existing xAssistant repository checkout:

```bash
cd ~/projects/xAssistant
git pull --ff-only
sudo bash server/deploy/install.sh
```

The installer verifies the configured LAN address, Ollama API, installed model,
and Python version before changing anything. It creates a dedicated
`xassistant` service account, installs the app under `/opt/xassistant-server`,
stores the environment in `/etc/xassistant-server.env`, and stores the database
under `/var/lib/xassistant`.

It does not download or remove models. It does not overwrite an existing
`/etc/xassistant-server.env`.

## LAN firewall

After reviewing the install, allow only the local subnet to reach the API:

```bash
sudo ufw allow from 192.168.88.0/24 to 192.168.88.50 port 5081 proto tcp
```

Do not add a public or Cloudflare ingress rule until authentication and HTTPS
remote-access design are implemented.

## Verify and inspect

```bash
curl -fsS http://192.168.88.50:5081/health
systemctl is-active xassistant-api
systemctl status xassistant-api --no-pager
journalctl -u xassistant-api -n 80 --no-pager
```

Expected health response:

```json
{"status":"ok","service":"xassistant-server"}
```

## Configuration

Edit `/etc/xassistant-server.env` to change runtime settings, then restart:

```bash
sudoedit /etc/xassistant-server.env
sudo systemctl restart xassistant-api
```

The installer preserves this file on subsequent runs. Database files are never
removed by the installer.
