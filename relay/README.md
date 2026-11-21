# PhoneDesk relay

A tiny WebSocket relay so your PhoneDesk phone is reachable from **any network** —
mobile data, another Wi-Fi, another country — with no Tailscale and no port-forwarding.

The phone dials **out** to this relay and registers a device id. Your browser loads
`https://<relay>/d/<device-id>/` and every request is tunnelled to the phone's local
PhoneDesk server. The PhoneDesk PIN still protects access end to end.

## Deploy on Render (free)

1. Push this repo to GitHub (already done if you're reading this there).
2. Go to https://dashboard.render.com → **New** → **Web Service** → connect the repo.
3. Set **Root Directory** to `relay`, **Build** `npm install`, **Start** `npm start`.
   (Or just use the `render.yaml` blueprint at the repo root — **New → Blueprint**.)
4. Add an environment variable **RELAY_SECRET** = any long random string. Keep it.
5. Deploy. You get a URL like `https://phonedesk-relay-xxxx.onrender.com`.

> Render's free tier sleeps after 15 min idle and wakes on the next request (a few
> seconds' delay). Fine for personal use. Fly.io and Railway work the same way.

## Point the app at it

In the PhoneDesk app → **Internet access** section:
- **Relay URL**: the `https://…onrender.com` URL
- **Relay secret**: the same `RELAY_SECRET`
- Tick **Enable internet access** → **Save & connect relay**

The app shows **Relay: online** and a `From anywhere, open:` URL. Open that URL in any
browser, on any network, and enter your PIN.

## Security

- Access needs both the device-id URL (random, unguessable) **and** the PhoneDesk PIN.
- `RELAY_SECRET` stops anyone else registering a phone on your relay.
- Traffic to the relay is HTTPS/WSS. The relay only forwards bytes; it never stores them.
- Only run a relay you control.
