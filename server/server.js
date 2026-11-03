// PRAMA SCREEN — signaling server (WebRTC offer/answer/ICE relay)
// Run: npm install && npm start
// Env: PORT, ADMIN_PIN (viewer pin), TURN_URL, TURN_USER, TURN_PASS
const http = require('http');
const { WebSocketServer } = require('ws');

const PORT = process.env.PORT || 8080;
const ADMIN_PIN = process.env.ADMIN_PIN || '1234';

function iceServers() {
  const list = [{ urls: ['stun:stun.l.google.com:19302', 'stun:stun1.l.google.com:19302'] }];
  if (process.env.TURN_URL) {
    list.push({
      urls: process.env.TURN_URL.split(',').map(s => s.trim()).filter(Boolean),
      username: process.env.TURN_USER || '',
      credential: process.env.TURN_PASS || '',
    });
  }
  return list;
}

// room code -> { sharer, viewer, name }
const rooms = new Map();

const server = http.createServer((req, res) => {
  res.writeHead(200, { 'content-type': 'text/plain' });
  res.end('PRAMA SCREEN SERVER OK — LIVE: ' + rooms.size);
});
const wss = new WebSocketServer({ server });

function send(ws, obj) {
  if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj));
}
function fail(ws, message) {
  send(ws, { type: 'error', message });
  setTimeout(() => { try { ws.close(); } catch (e) {} }, 200);
}

wss.on('connection', ws => {
  ws.isAlive = true;
  ws.on('pong', () => { ws.isAlive = true; });

  ws.on('message', raw => {
    let m;
    try { m = JSON.parse(raw); } catch (e) { return; }

    switch (m.type) {
      case 'list': {
        if (String(m.pin) !== ADMIN_PIN) return fail(ws, 'WRONG ADMIN PIN');
        const list = [...rooms.entries()].map(([room, r]) => ({ room, name: r.name, watching: !!r.viewer }));
        return send(ws, { type: 'rooms', rooms: list });
      }

      case 'join': {
        const room = String(m.room || '').trim();
        if (!room) return fail(ws, 'NO ROOM CODE');
        ws.room = room;
        ws.role = m.role;

        if (m.role === 'sharer') {
          const old = rooms.get(room);
          if (old && old.sharer && old.sharer !== ws) { try { old.sharer.terminate(); } catch (e) {} }
          const r = { sharer: ws, viewer: old ? old.viewer : null, name: String(m.name || 'STAFF').toUpperCase() };
          rooms.set(room, r);
          send(ws, { type: 'joined', ice: iceServers() });
          if (r.viewer) send(ws, { type: 'peer-joined' });
          console.log('SHARER ONLINE', room, r.name);
        } else if (m.role === 'viewer') {
          if (String(m.pin) !== ADMIN_PIN) return fail(ws, 'WRONG ADMIN PIN');
          const r = rooms.get(room);
          if (!r || !r.sharer) return fail(ws, 'STAFF NOT ONLINE');
          if (r.viewer && r.viewer !== ws) fail(r.viewer, 'ANOTHER ADMIN OPENED THIS SCREEN');
          r.viewer = ws;
          send(ws, { type: 'joined', ice: iceServers() });
          send(r.sharer, { type: 'peer-joined' });
          console.log('VIEWER JOINED', room);
        } else {
          fail(ws, 'BAD ROLE');
        }
        return;
      }

      case 'offer':
      case 'answer':
      case 'candidate': {
        const r = rooms.get(ws.room);
        if (!r) return;
        const other = ws === r.sharer ? r.viewer : ws === r.viewer ? r.sharer : null;
        send(other, m);
        return;
      }
    }
  });

  ws.on('close', () => {
    const r = rooms.get(ws.room);
    if (!r) return;
    if (r.sharer === ws) {
      send(r.viewer, { type: 'peer-left' });
      rooms.delete(ws.room);
      console.log('SHARER LEFT', ws.room);
    } else if (r.viewer === ws) {
      r.viewer = null;
      send(r.sharer, { type: 'peer-left' });
      console.log('VIEWER LEFT', ws.room);
    }
  });
});

// Drop dead connections (phone lost network etc.)
setInterval(() => {
  wss.clients.forEach(ws => {
    if (!ws.isAlive) return ws.terminate();
    ws.isAlive = false;
    try { ws.ping(); } catch (e) {}
  });
}, 25000);

server.listen(PORT, () => console.log('PRAMA SCREEN SERVER ON PORT ' + PORT));
