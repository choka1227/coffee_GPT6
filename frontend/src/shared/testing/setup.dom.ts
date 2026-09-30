let uuidSequence = 0;

if (!HTMLDialogElement.prototype.showModal)
  HTMLDialogElement.prototype.showModal = function () {
    this.open = true;
  };

if (!HTMLDialogElement.prototype.close)
  HTMLDialogElement.prototype.close = function () {
    this.open = false;
  };

if (!globalThis.crypto?.randomUUID) {
  const randomUUID = () =>
    `00000000-0000-4000-8000-${(++uuidSequence).toString().padStart(12, "0")}` as `${string}-${string}-${string}-${string}-${string}`;
  if (!globalThis.crypto)
    Object.defineProperty(globalThis, "crypto", { value: {}, configurable: true });
  Object.defineProperty(globalThis.crypto, "randomUUID", {
    value: randomUUID,
    configurable: true,
  });
}
