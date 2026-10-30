#pragma once
#include <stdint.h>
#include <string>

// Transport-independent mutual authentication. The secret and random generator
// stay outside this policy; neither proofs nor credentials are logged.
class WearableSession {
public:
  typedef std::string (*Proof)(const std::string &);
  explicit WearableSession(Proof proof) : proof_(proof) {}
  void start(uint32_t session, uint32_t now) {
    session_ = session; started_ = now; state_ = Hello;
    client_.clear(); server_.clear();
  }
  void clear() { start(0, 0); state_ = Rejected; }
  uint32_t session() const { return session_; }
  bool authenticated() const { return state_ == Accepted; }
  bool expired(uint32_t now) const { return uint32_t(now - started_) >= 10000; }

  // true means a complete valid protocol step; every other input fails closed.
  bool receive(const std::string &line, const std::string &random, std::string &reply) {
    reply.clear();
    if (state_ == Hello && line.size() == 42 && line.compare(0, 10, "CM1 HELLO ") == 0
        && hex(line.substr(10), 32) && hex(random, 32)) {
      client_ = line.substr(10); server_ = random;
      const std::string signature = proof_(transcript("board"));
      if (!hex(signature, 64)) return reject();
      reply = "CM1 CHALLENGE elder-demo-01 " + server_ + " " + signature;
      state_ = Auth; return true;
    }
    if (state_ == Auth && line.size() == 73 && line.compare(0, 9, "CM1 AUTH ") == 0
        && hex(line.substr(9), 64) && equal(line.substr(9), proof_(transcript("phone")))) {
      const std::string signature = proof_(transcript("ready"));
      if (!hex(signature, 64)) return reject();
      reply = "CM1 READY " + signature;
      state_ = Accepted; return true;
    }
    return reject();
  }
  static bool hex(const std::string &value, size_t length) {
    if (value.size() != length) return false;
    for (char c : value) if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) return false;
    return true;
  }
  static bool equal(const std::string &a, const std::string &b) {
    if (a.size() != 64 || b.size() != 64) return false;
    unsigned int different = 0;
    for (size_t i = 0; i < 64; ++i) different |= static_cast<unsigned char>(a[i] ^ b[i]);
    return different == 0;
  }
private:
  enum State { Hello, Auth, Accepted, Rejected };
  State state_ = Rejected;
  Proof proof_;
  uint32_t session_ = 0, started_ = 0;
  std::string client_, server_;
  bool reject() { state_ = Rejected; return false; }
  std::string transcript(const char *role) const {
    return std::string("CM1|") + role + "|elder-demo-01|" + client_ + "|" + server_;
  }
};
