#!/usr/bin/env python3
import socket
import threading
import time
import queue
from dataclasses import dataclass, field

HOST = "127.0.0.1"   # localhost
PORT = 25590

SENDQ_MAX = 20000
DEBUG_PRINT = False   # set True if you want to see every relayed line

lock = threading.RLock()

@dataclass
class Client:
    sock: socket.socket
    addr: tuple
    sendq: "queue.Queue[str]" = field(default_factory=lambda: queue.Queue(maxsize=SENDQ_MAX))
    alive: bool = True
    last_focus_ts: float = 0.0
    focused: bool = False

clients: list[Client] = []
source: Client | None = None  # leader


def log(*a):
    print(*a, flush=True)


def send_line(c: Client, line: str):
    """Enqueue a line for async send. Drops if queue is full (shouldn't happen on localhost)."""
    if not c.alive:
        return
    try:
        c.sendq.put_nowait(line.rstrip("\n") + "\n")
    except queue.Full:
        # If this happens, the client is not reading or is overloaded.
        # Dropping is better than stalling the entire relay.
        pass


def writer_loop(c: Client):
    """Dedicated writer thread per client; prevents one slow client from blocking others."""
    try:
        while c.alive:
            try:
                line = c.sendq.get(timeout=0.5)
            except queue.Empty:
                continue
            if not line:
                continue
            try:
                c.sock.sendall(line.encode("utf-8"))
            except OSError:
                break
    finally:
        # Let the reader / server cleanup handle removal.
        c.alive = False


def broadcast_roles():
    """Tell each client whether they're leader/follower."""
    global source
    with lock:
        s = source
        snapshot = list(clients)
    for c in snapshot:
        try:
            send_line(c, "ROLE_LEADER" if (s is not None and c is s) else "ROLE_FOLLOWER")
        except Exception:
            pass


def pick_source_locked():
    """Pick most recently focused; fallback to first connected."""
    global source
    if not clients:
        source = None
        return

    best = None
    best_ts = -1.0
    for c in clients:
        if c.focused and c.last_focus_ts > best_ts:
            best_ts = c.last_focus_ts
            best = c

    source = best if best is not None else clients[0]


def set_source_locked(new_source: Client | None):
    """Set leader and broadcast roles immediately."""
    global source
    source = new_source
    broadcast_roles()
    if source is not None:
        try:
            idx = clients.index(source)
            log(f"[SOURCE] now {idx} {source.addr} (focused={source.focused})")
        except Exception:
            log("[SOURCE] updated")
    else:
        log("[SOURCE] none")


def drop_client(c: Client):
    global source
    with lock:
        if c in clients:
            clients.remove(c)
        was_source = (source is c)
        c.alive = False
        try:
            c.sock.close()
        except OSError:
            pass

        if was_source:
            pick_source_locked()
            set_source_locked(source)
        else:
            broadcast_roles()


def broadcast(line: str, exclude: Client | None = None):
    with lock:
        snapshot = list(clients)
    for c in snapshot:
        if exclude is not None and c is exclude:
            continue
        send_line(c, line)


def handle_line(c: Client, line: str):
    """Process one incoming line from a client."""
    global source

    if not line:
        return

    # Focus updates (server-only)
    if line == "FOCUS_1":
        with lock:
            c.focused = True
            c.last_focus_ts = time.time()
            pick_source_locked()
            # If this client became leader, announce immediately
            if source is c:
                set_source_locked(c)
            else:
                broadcast_roles()
        return

    if line == "FOCUS_0":
        with lock:
            c.focused = False
            # If leader unfocused, pick best available and announce
            if source is c:
                pick_source_locked()
                set_source_locked(source)
            else:
                broadcast_roles()
        return

    with lock:
        is_source = (source is not None and c is source)

    # Only accept these from source temporairly
    if line.startswith(("UI_", "STATE_", "CHAT_", "CMD_", "MOVE_", "INV_", "HBAR_", "OPEN_", "CLOSE_", "PAUSE", "RESUME", "TOGGLE_")):
        if not is_source and line.startswith(("UI_", "STATE_", "CHAT_", "CMD_", "MOVE_", "INV_", "HBAR_", "OPEN_", "CLOSE_")):
            return

    if DEBUG_PRINT:
        log(line)

    # Relay to everyone else
    broadcast(line, exclude=c)


def client_loop(conn: socket.socket, addr):
    global source
    c = Client(sock=conn, addr=addr)

    try:
        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)

        # Add client & select leader if needed
        with lock:
            clients.append(c)
            if source is None:
                pick_source_locked()
                set_source_locked(source)
            else:
                broadcast_roles()

        threading.Thread(target=writer_loop, args=(c,), daemon=True).start()

        log(f"[+] {addr} connected (clients={len(clients)})")

        buf = b""
        while c.alive:
            data = conn.recv(4096)
            if not data:
                break
            buf += data
            while b"\n" in buf:
                raw, buf = buf.split(b"\n", 1)
                line = raw.decode("utf-8", "replace").strip()
                handle_line(c, line)

    except OSError:
        pass
    finally:
        log(f"[-] {addr} disconnected")
        drop_client(c)


def accept_loop(server_sock: socket.socket):
    while True:
        conn, addr = server_sock.accept()
        threading.Thread(target=client_loop, args=(conn, addr), daemon=True).start()


def console_loop():
    log("Console: pause | resume | toggle | clients | source | quit")
    while True:
        cmd = input("> ").strip().lower()
        if cmd in ("q", "quit", "exit"):
            return
        if cmd == "pause":
            broadcast("PAUSE")
            continue
        if cmd == "resume":
            broadcast("RESUME")
            continue
        if cmd == "toggle":
            broadcast("TOGGLE_SYNC")
            continue
        if cmd == "source":
            with lock:
                if source is not None and source in clients:
                    i = clients.index(source)
                    log(f"source = {i} {source.addr} (focused={source.focused})")
                else:
                    log("source = none")
            continue
        if cmd == "clients":
            with lock:
                for i, cl in enumerate(clients):
                    mark = " (SOURCE)" if (source is cl) else ""
                    foc = " (FOCUSED)" if cl.focused else ""
                    log(f"{i}: {cl.addr}{mark}{foc}")
            continue
        log("Unknown command.")


def main():
    s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((HOST, PORT))
    s.listen()
    log(f"[InputSync Relay] Listening on {HOST}:{PORT}")

    threading.Thread(target=accept_loop, args=(s,), daemon=True).start()
    console_loop()


if __name__ == "__main__":
    main()

