'use strict';
/*
 * PhoneDesk relay.
 *
 * The phone's PhoneDesk app opens an outbound WebSocket to  wss://<this>/agent
 * and registers a device id. A browser then loads  https://<this>/d/<id>/...
 * and every request is tunnelled over that WebSocket to the phone's local
 * PhoneDesk server (127.0.0.1:8080) and the response streamed back.
 *
 * Because the phone dials OUT, this works through any NAT / mobile data with no
 * port-forwarding. The PhoneDesk PIN still protects access end to end.
 *
 * One env var:  RELAY_SECRET  — a phone may register only if it sends this.
 * Deploy on Render/Fly/Railway (free tier). Node 18+.
 */

const http = require('http');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 10000;
const SECRET = process.env.RELAY_SECRET || '';

const agents = new Map();            // deviceId -> ws

function i32(n) { return Buffer.from([(n >>> 24) & 255, (n >>> 16) & 255, (n >>> 8) & 255, n & 255]); }

const HOP = new Set(['connection', 'keep-alive', 'transfer-encoding', 'upgrade', 'proxy-connection']);
function clean(headers) {
  const o = {};
  for (const k in headers) if (!HOP.has(k.toLowerCase())) o[k] = headers[k];
  return o;
}

const server = http.createServer((req, res) => {
  const m = /^\/d\/([^/]+)(\/[^?]*)?(\?.*)?$/.exec(req.url);
  if (!m) {
    res.writeHead(200, { 'Content-Type': 'text/html; charset=utf-8' });
    res.end('<h2>PhoneDesk relay</h2><p>Running. Open <code>/d/&lt;device-id&gt;/</code> ' +
            'with the id shown in your PhoneDesk app.</p>');
    return;
  }
  const id = m[1];
  const path = (m[2] || '/') + (m[3] || '');
  const ws = agents.get(id);
  if (!ws || ws.readyState !== ws.OPEN) {
    res.writeHead(502, { 'Content-Type': 'text/plain' });
    res.end('Device offline. Open PhoneDesk on the phone and turn on remote access.');
    return;
  }

  const sid = ws._nextSid++;
  ws._streams.set(sid, res);
  ws.send(JSON.stringify({ t: 'req', sid, method: req.method, path, headers: clean(req.headers) }));

  req.on('data', (chunk) => { try { ws.send(Buffer.concat([i32(sid), chunk])); } catch (e) {} });
  req.on('end', () => { try { ws.send(JSON.stringify({ t: 'reqend', sid })); } catch (e) {} });

  const gone = () => {
    if (ws._streams.has(sid)) {
      ws._streams.delete(sid);
      try { ws.send(JSON.stringify({ t: 'close', sid })); } catch (e) {}
    }
  };
  res.on('close', gone);
});

const wss = new WebSocketServer({ server, path: '/agent', maxPayload: 32 * 1024 * 1024 });

wss.on('connection', (ws) => {
  ws._streams = new Map();
  ws._nextSid = 1;
  ws._id = null;
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', (data, isBinary) => {
    if (isBinary) {
      if (data.length < 4) return;
      const sid = data.readUInt32BE(0);
      const res = ws._streams.get(sid);
      if (res) res.write(data.subarray(4));
      return;
    }
    let msg;
    try { msg = JSON.parse(data.toString()); } catch (e) { return; }

    if (msg.t === 'hello') {
      if (SECRET && msg.secret !== SECRET) { ws.close(4001, 'bad secret'); return; }
      if (!msg.id) { ws.close(4002, 'no id'); return; }
      const old = agents.get(msg.id);
      if (old && old !== ws) try { old.close(4003, 'replaced'); } catch (e) {}
      ws._id = msg.id;
      agents.set(msg.id, ws);
      ws.send(JSON.stringify({ t: 'ready' }));
      return;
    }
    const res = ws._streams.get(msg.sid);
    if (!res) return;
    if (msg.t === 'res') {
      try { res.writeHead(msg.status || 200, clean(msg.headers || {})); } catch (e) {}
    } else if (msg.t === 'resend') {
      try { res.end(); } catch (e) {}
      ws._streams.delete(msg.sid);
    }
  });

  ws.on('close', () => {
    if (ws._id && agents.get(ws._id) === ws) agents.delete(ws._id);
    for (const res of ws._streams.values()) try { res.end(); } catch (e) {}
    ws._streams.clear();
  });
  ws.on('error', () => {});
});

// Drop dead agent sockets.
setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.isAlive === false) return ws.terminate();
    ws.isAlive = false;
    try { ws.ping(); } catch (e) {}
  });
}, 30000);

server.listen(PORT, () => console.log('PhoneDesk relay on :' + PORT));
