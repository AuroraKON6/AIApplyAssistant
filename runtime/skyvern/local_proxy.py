"""
Local HTTP proxy that forwards requests to the Xiaomi MiMo API.
Uses http.client instead of httpx (which has TLS renegotiation issues on Windows).
"""
import json
import os
import ssl
import http.client
from http.server import HTTPServer, BaseHTTPRequestHandler

try:
    from dotenv import load_dotenv
    load_dotenv()
except Exception:
    pass

TARGET_HOST = "token-plan-sgp.xiaomimimo.com"
API_KEY = os.getenv("MIMO_API_KEY") or os.getenv("OPENAI_COMPATIBLE_API_KEY") or ""
PORT = 8002

SSL_CTX = ssl.create_default_context()


class ProxyHandler(BaseHTTPRequestHandler):
    def do_POST(self):
        content_length = int(self.headers.get('Content-Length', 0))
        body = self.rfile.read(content_length) if content_length > 0 else b''

        target_path = self.path
        headers = {
            'Content-Type': 'application/json',
            'x-api-key': API_KEY,
            'Authorization': f'Bearer {API_KEY}',
        }

        try:
            conn = http.client.HTTPSConnection(TARGET_HOST, context=SSL_CTX, timeout=300)
            conn.request('POST', target_path, body=body, headers=headers)
            resp = conn.getresponse()
            resp_body = resp.read()
            conn.close()

            self.send_response(resp.status)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(resp_body)
        except Exception as e:
            self.send_response(502)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps({"error": str(e)}).encode())

    def do_GET(self):
        target_path = self.path
        headers = {
            'x-api-key': API_KEY,
            'Authorization': f'Bearer {API_KEY}',
        }

        try:
            conn = http.client.HTTPSConnection(TARGET_HOST, context=SSL_CTX, timeout=30)
            conn.request('GET', target_path, headers=headers)
            resp = conn.getresponse()
            resp_body = resp.read()
            conn.close()

            self.send_response(resp.status)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(resp_body)
        except Exception as e:
            self.send_response(502)
            self.send_header('Content-Type', 'application/json')
            self.end_headers()
            self.wfile.write(json.dumps({"error": str(e)}).encode())

    def log_message(self, format, *args):
        print(f"[PROXY] {args[0]}")

if __name__ == '__main__':
    if not API_KEY:
        raise SystemExit("MIMO_API_KEY is required to start the MiMo proxy.")
    server = HTTPServer(('127.0.0.1', PORT), ProxyHandler)
    print(f"Local proxy running on http://127.0.0.1:{PORT}")
    print(f"Forwarding to https://{TARGET_HOST}")
    server.serve_forever()
