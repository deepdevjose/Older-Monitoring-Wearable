"""Exercise the actual firmware authentication state machine on a host compiler."""
from pathlib import Path
import shutil
import subprocess

import pytest


def test_firmware_mutual_authentication_and_replay_boundaries(tmp_path):
    compiler = shutil.which("g++")
    if not compiler:
        pytest.skip("Host C++ compiler unavailable")
    root = Path(__file__).resolve().parents[1]
    source = tmp_path / "auth.cpp"
    source.write_text(r'''
#include "WearableSession.h"
#include <cassert>
// A deterministic injected proof isolates state policy from the platform's
// SHA256 primitive. Android tests and physical tests exercise real HMAC-SHA256.
std::string proof(const std::string &s) {
  uint64_t hash = 14695981039346656037ULL;
  for (unsigned char c : s) { hash ^= c; hash *= 1099511628211ULL; }
  const char *digits = "0123456789abcdef";
  std::string out(64, '0');
  for (int i=0;i<64;i++) out[i]=digits[(hash >> (4*(i%16))) & 15];
  return out;
}
const std::string client(32, 'a'), nonce(32, 'b'), fresh(32, 'c');
std::string phone(const std::string &n) {
  return "CM1 AUTH " + proof("CM1|phone|elder-demo-01|"+client+"|"+n);
}
int main() {
  WearableSession a(proof); std::string reply;
  assert(!a.authenticated());
  a.start(7, 100); assert(a.session()==7 && !a.expired(10099) && a.expired(10100));
  assert(!a.receive(phone(nonce), nonce, reply)); // no HELLO/challenge
  a.start(7,100);
  assert(a.receive("CM1 HELLO "+client, nonce, reply));
  assert(reply=="CM1 CHALLENGE elder-demo-01 "+nonce+" "+proof("CM1|board|elder-demo-01|"+client+"|"+nonce));
  assert(!a.authenticated());
  assert(a.receive(phone(nonce), fresh, reply));
  assert(a.authenticated());
  assert(reply=="CM1 READY "+proof("CM1|ready|elder-demo-01|"+client+"|"+nonce));
  assert(!a.receive(phone(nonce), fresh, reply)); // single-use proof
  assert(!a.authenticated());
  a.start(8,200); assert(a.receive("CM1 HELLO "+client, fresh, reply));
  assert(!a.receive(phone(nonce), fresh, reply)); // replay on a fresh nonce
  a.start(9,200); assert(a.receive("CM1 HELLO "+client, nonce, reply));
  assert(!a.receive("CM1 AUTH "+proof("CM1|board|elder-demo-01|"+client+"|"+nonce),nonce,reply)); // role reflection
  for (const std::string &bad : {"CM1 HELLO "+std::string(32,'A'), "CM1 HELLO "+client+" extra", "CM1 HELLO "+std::string(31,'a'), std::string("{\"device_id\":\"elder-demo-01\"}")}) {
    a.start(10,0); assert(!a.receive(bad,nonce,reply)); assert(reply.empty() && !a.authenticated());
  }
  a.start(11,0xfffffff0U); assert(!a.expired(0x10U)); assert(a.expired(0x3000U));
  a.clear(); assert(a.session()==0 && !a.authenticated());
  assert(!WearableSession::equal(std::string(63,'a'),std::string(63,'a')));
  assert(!WearableSession::equal(std::string(64,'a'),std::string(63,'a')+"b"));
}
''')
    binary = tmp_path / "auth"
    subprocess.run([compiler, "-std=c++11", "-Wall", "-Wextra", "-Werror", "-I",
                    str(root / "firmware/esp32"), str(source), "-o", str(binary)],
                   check=True, capture_output=True, text=True)
    subprocess.run([str(binary)], check=True, capture_output=True, text=True)
