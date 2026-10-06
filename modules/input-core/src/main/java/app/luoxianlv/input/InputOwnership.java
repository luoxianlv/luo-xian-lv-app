package app.luoxianlv.input;

/** 物理捕获的单一所有权；收到精确释放确认或助手死亡后才允许下一租约接管。 */
final class InputOwnership<T> {
  private volatile T owner;
  private long releaseTicket;

  T owner() { return owner; }

  synchronized boolean claim(T value) {
    if (owner != null && (owner != value || releaseTicket != 0)) return false;
    owner = value;
    return true;
  }

  synchronized boolean releasing(T value, long ticket) {
    if (owner != value || ticket <= 0) return false;
    releaseTicket = ticket;
    return true;
  }

  synchronized T acknowledge(long ticket, boolean active) {
    if (active || ticket <= 0 || ticket != releaseTicket) return null;
    return clear();
  }

  synchronized T clear() {
    T previous = owner;
    owner = null;
    releaseTicket = 0;
    return previous;
  }
}
