// One preparation/player owns admission until its shared destruction finishes.
export class TwitchOwnedLifetime {
  private occupied = false;
  private retirement?: Promise<void>;
  private failed = false;
  private readonly empty = Promise.resolve();

  get retiring(): boolean { return this.retirement !== undefined; }

  acquire(): boolean {
    if (this.occupied || this.retirement || this.failed) return false;
    this.occupied = true;
    return true;
  }

  stop(cleanup: () => Promise<void>): Promise<void> {
    if (this.retirement) return this.retirement;
    if (this.failed) return Promise.reject(new Error('Cleanup unconfirmed'));
    if (!this.occupied) return this.empty;
    this.retirement = Promise.resolve().then(cleanup).then(() => {
      this.occupied = false;
      this.retirement = undefined;
    }, () => {
      this.failed = true;
      throw new Error('Cleanup unconfirmed');
    });
    return this.retirement;
  }
}
