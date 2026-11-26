// -*- mode: Bluespec; -*-
/**
 * The key migration tools of 1.4.0 (issue #1014, PR #1025), with the fixes before 1.4.0
 * (docs/design/key-migration-fix-before-1.4.0.md): JournalKeyMigration, SnapshotKeyMigration, and base-v2
 * migration.{ResolverKeyPlan, KeyMigration, KeyVerification}. A target key whose winner did not reach it is
 * reserved for that winner for the rest of the run (review C-F1).
 *
 * What is modelled:
 *  - two tables (journal, snapshot), each a map from a stored key to a row, and the persistence-id
 *    index of each table as a copy that lags behind the table key by key;
 *  - the plan (ResolverKeyPlan.placement) as a pure function of the persistence id, the sequence
 *    number, and the stored key, over a fixed universe of keys that holds every case the code
 *    distinguishes (see the universe below);
 *  - a run of the tool: the scan, the rounds of `execute` (the winner per target key, the rows whose
 *    target is held by a pending row wait), and per candidate the attempts of `moveOne`: one
 *    TransactWriteItems, applied atomically, whose response can be lost, a transient failure, and
 *    `settle` after the last attempt;
 *  - a stop of the tool at any point (the process dies; the table keeps what the transactions did),
 *    and a new run with any direction, configured resolvers, and --persistence-id selection;
 *  - --verify, as one atomic look at the table and the index;
 *  - the asynchronous update of the index.
 *
 * The applications that write to the tables are NOT in this module: the contract of the tools is that
 * they are stopped (--writers-stopped). keyMigrationWriters.qnt adds them to show what breaks.
 *
 * Correspondence with the code: docs/design/key-migration.md, section "Quint".
 */
module keyMigration {

  // ---------------------------------------------------------------------------------------------
  // Types
  // ---------------------------------------------------------------------------------------------

  type Key = str
  type Id = str
  /** A logical row: the persistence id and the sequence number (the identity that `isRowOf` reads). */
  type LRow = (Id, int)
  /**
   * A stored row. `payload` stands for every attribute that `differences` compares (message, tags,
   * ordering, snapshot); `deleted` is the duplicate-rule column of the journal; `created` that of the
   * snapshot store.
   */
  type Row = { id: Id, seq: int, payload: int, deleted: bool, created: int }
  type Table = Key -> Row

  type Store = Journal | Snapshot
  type Dir = Forward | Reverse
  /** KeyForm: the configured resolvers are F130 or FPlain; F831 is only a form a row moves from. */
  type Form = F130 | F831 | FPlain
  type Placement = InPlace | Move({ to: Key, from: Form }) | Unmovable(str)

  type Cand = { id: Id, seq: int, src: Key, dst: Key }
  type Outcome =
    | Moved
    | Merged
    | Gone
    | Blocked(LRow)
    | Conflict
    | Failed(str)

  type Phase = Idle | Running | Finished
  type Params = { dir: Dir, form: Form, sel: Set[Id] }

  type Tool = {
    phase: Phase,
    params: Params,
    pending: Set[Cand],        // KeyMigration.execute: `pending`
    round: Set[Cand],          // the `ready` of the current round that has no outcome yet
    attempts: Cand -> int,     // moveOne: `attempt`
    merging: Set[Cand],        // moveOne: `merging`
    outcomes: Set[(Cand, Outcome)],
    remaining: Set[Cand],      // MigrationReport.remaining
    unmovable: Set[Key],       // MigrationReport.unmovable
    gaveUp: bool,              // a transient failure ended in Failed (settle)
    epochGaveUp: bool,         // an earlier run of the same epoch gave up (history, read by the witnesses only)
    reserved: Set[Key],        // KeyMigration.execute: `reserved`, the targets whose winner ended without the row there
    startTable: Table,         // the table when this run started (for the selection invariant)
  }

  // ---------------------------------------------------------------------------------------------
  // The universe of keys (the resolver functions, abstracted to maps)
  // ---------------------------------------------------------------------------------------------

  /**
   *  a  : its new key is the old key of b            -> moves after b (ordered move)
   *  b  : plain                                     -> direct move
   *  c,d: each new key is the other's old key        -> cycle, not moved
   *  e  : its new key is the old key of f, and f cannot move (too large under its new key)
   *                                                 -> blocked by a row that does not move
   *  f  : too large under its new key (ValidationException) -> Failed, stays
   *  g  : no separator, the same key in both formats, and a row of issue #831
   *  x  : no separator, the same #831 key as g (the shard and "%019d" of the sequence number)
   *  h,i: share their key of 1.3.0 (issue #1009); new keys differ
   *  j  : 1.3.0 cannot build its key (e.g. `order-`)  -> cannot go back
   *  k  : may have a copy under both keys (equal, or different contents)
   *  u  : may be stored under a key of no known form (e.g. another shard-count)
   */
  pure val LROWS: Set[LRow] = Set(
    ("a", 1), ("b", 1), ("c", 1), ("d", 1), ("e", 1), ("f", 1), ("g", 1), ("x", 1),
    ("h", 1), ("i", 1), ("j", 1), ("k", 1), ("u", 1)
  )
  pure val IDS: Set[Id] = LROWS.map(l => l._1)

  /** release130: the key of PersistenceIdBased / PersistenceIdWithSeqNr; absent = None. */
  pure val R130: LRow -> Key = Map(
    ("a", 1) -> "a130", ("b", 1) -> "b130", ("c", 1) -> "c130", ("d", 1) -> "d130",
    ("e", 1) -> "e130", ("f", 1) -> "f130", ("g", 1) -> "g130", ("x", 1) -> "x130",
    ("h", 1) -> "h130", ("i", 1) -> "h130", ("k", 1) -> "k130", ("u", 1) -> "u130"
  )
  /** plainText: the key of the PlainText resolvers; always defined, never shared by two ids. */
  pure val PLAIN: LRow -> Key = Map(
    ("a", 1) -> "b130", ("b", 1) -> "bP", ("c", 1) -> "d130", ("d", 1) -> "c130",
    ("e", 1) -> "f130", ("f", 1) -> "fP", ("g", 1) -> "g130", ("x", 1) -> "x130",
    ("h", 1) -> "hP", ("i", 1) -> "iP", ("j", 1) -> "jP", ("k", 1) -> "kP", ("u", 1) -> "uP"
  )
  /** The key of issue #831 (same pkey as the target, skey = "%019d" of the sequence number). */
  pure val K831: LRow -> Key = Map(("g", 1) -> "g831", ("x", 1) -> "g831")
  /** A key of no known form (another shard-count, separator, or resolver). */
  pure val UNKNOWN: LRow -> Key = Map(("u", 1) -> "uX")
  /** Too large (400 KB) under its new key: the Put is rejected with a ValidationException. */
  pure val BIG: Set[LRow] = Set(("f", 1))
  /**
   * With `rejectShared` (fixed per trace), h is too large under the longer key of 1.3.0, so its --reverse move to
   * the shared key h130 is rejected too (review R3: the winner of a shared target rejected by DynamoDB).
   */
  pure val BIG_SHARED: (LRow, Key) = (("h", 1), "h130")
  /** ResolverKeyPlan.sharedKeyGroup: the key of 1.3.0 without the sequence number; None for j. */
  pure val GROUP: Id -> str = Map(
    "a" -> "a", "b" -> "b", "c" -> "c", "d" -> "d", "e" -> "e", "f" -> "f", "g" -> "g",
    "x" -> "x", "h" -> "hi", "i" -> "hi", "k" -> "k", "u" -> "u"
  )
  /** The order of the stored keys (`winnerOf` takes the smallest stored key). */
  pure val ORD: Key -> int = Map(
    "a130" -> 1, "b130" -> 2, "bP" -> 3, "c130" -> 4, "d130" -> 5, "e130" -> 6, "f130" -> 7,
    "fP" -> 8, "g130" -> 9, "g831" -> 10, "x130" -> 11, "h130" -> 12, "hP" -> 13, "iP" -> 14,
    "jP" -> 15, "k130" -> 16, "kP" -> 17, "u130" -> 18, "uP" -> 19, "uX" -> 20
  )
  pure val STORES: Set[Store] = Set(Journal, Snapshot)
  pure val MAX_ATTEMPTS = 2   // MigrationSettings.maxAttempts (10 in the code)

  // ---------------------------------------------------------------------------------------------
  // Small helpers
  // ---------------------------------------------------------------------------------------------

  pure def has(m: a -> b, k: a): bool = m.keys().contains(k)
  pure def remove(m: Key -> Row, k: Key): Key -> Row = m.keys().exclude(Set(k)).mapBy(x => m.get(x))
  pure def lrow(r: Row): LRow = (r.id, r.seq)
  pure def isRowOf(r: Row, c: Cand): bool = r.id == c.id and r.seq == c.seq
  pure def candRow(c: Cand): LRow = (c.id, c.seq)
  pure def copiesIn(t: Table, l: LRow): Set[Key] = t.keys().filter(k => lrow(t.get(k)) == l)
  pure def presentRows(t: Table): Set[LRow] = t.keys().map(k => lrow(t.get(k)))
  /** The keys a copy of `l` may be under: its forms and the key of no form. */
  pure def knownKeys(l: LRow): Set[Key] =
    Set(PLAIN.get(l))
      .union(if (R130.has(l)) Set(R130.get(l)) else Set())
      .union(if (K831.has(l)) Set(K831.get(l)) else Set())
      .union(if (UNKNOWN.has(l)) Set(UNKNOWN.get(l)) else Set())

  // ---------------------------------------------------------------------------------------------
  // The plan: ResolverKeyPlan.placement
  // ---------------------------------------------------------------------------------------------

  pure val UNKNOWN_REASON = "unknown key"

  pure def is831(l: LRow, stored: Key): bool = K831.has(l) and K831.get(l) == stored

  pure def placement(dir: Dir, form: Form, l: LRow, stored: Key): Placement =
    if (dir == Forward) {
      // PlainText is always defined; the 1.3.0 key may not be.
      if (form == F130 and not(R130.has(l)))
        Unmovable("the configured key resolvers cannot build a key for this persistence id")
      else {
        val target = if (form == FPlain) PLAIN.get(l) else R130.get(l)
        if (target == stored) InPlace
        else if (R130.has(l) and R130.get(l) == stored) Move({ to: target, from: F130 })
        else if (is831(l, stored)) Move({ to: target, from: F831 })
        else if (PLAIN.get(l) == stored) Unmovable("stored under the key of the new format; run with --reverse")
        else Unmovable(UNKNOWN_REASON)
      }
    } else {
      if (R130.has(l)) {
        val key = R130.get(l)
        if (key == stored) InPlace
        else if (is831(l, stored)) Move({ to: key, from: F831 })
        else if (PLAIN.get(l) == stored) Move({ to: key, from: FPlain })
        else Unmovable(UNKNOWN_REASON)
      } else if (PLAIN.get(l) == stored)
        Unmovable("1.3.0 cannot build a key for this persistence id, so its rows cannot go back")
      else Unmovable(UNKNOWN_REASON)
    }

  pure def isInPlace(p: Placement): bool = match p { | InPlace => true | _ => false }

  // ---------------------------------------------------------------------------------------------
  // The scan: KeyMigration.scanTable (consistent reads; the tool runs alone)
  // ---------------------------------------------------------------------------------------------

  pure def scanCandidates(t: Table, p: Params): Set[Cand] =
    t.keys().filter(k => p.sel.contains(t.get(k).id)).fold(Set(), (acc, k) => {
      val r = t.get(k)
      match placement(p.dir, p.form, lrow(r), k) {
        | Move(m) => acc.union(Set({ id: r.id, seq: r.seq, src: k, dst: m.to }))
        | _ => acc
      }
    })

  pure def scanUnmovable(t: Table, p: Params): Set[Key] =
    t.keys().filter(k => p.sel.contains(t.get(k).id)).filter(k => {
      val r = t.get(k)
      match placement(p.dir, p.form, lrow(r), k) { | Unmovable(_) => true | _ => false }
    })

  // ---------------------------------------------------------------------------------------------
  // The rounds of KeyMigration.execute
  // ---------------------------------------------------------------------------------------------

  /** winnerOf: of the candidates that need the same target, the one with the smallest stored key. */
  pure def winnerOf(group: Set[Cand]): Cand =
    group.fold({ id: "", seq: 0, src: "", dst: "" }, (best, c) =>
      if (best.src == "" or ORD.get(c.src) < ORD.get(best.src)) c else best)

  /**
   * `ready`: one winner per target, and only when no pending row holds the target. A reserved target (its winner
   * ended in this run without the row there) is given to no other candidate.
   */
  pure def readyOf(pending: Set[Cand], reserved: Set[Key]): Set[Cand] = {
    val sources = pending.map(c => c.src)
    pending.map(c => c.dst)
      .filter(d => not(reserved.contains(d)))
      .map(d => winnerOf(pending.filter(c => c.dst == d)))
      .filter(c => not(sources.contains(c.dst)))
  }

  // ---------------------------------------------------------------------------------------------
  // One attempt of moveOne, and its transactions
  // ---------------------------------------------------------------------------------------------

  type Effect = { table: Table, outcome: Outcome }

  /** The Put is rejected for an item over 400 KB under its (longer) new key, or, with `big`, under h130. */
  pure def tooBig(big: bool, c: Cand): bool =
    (BIG.contains(candRow(c)) and c.dst == PLAIN.get(candRow(c))) or (big and (candRow(c), c.dst) == BIG_SHARED)

  /** `differences`: every attribute but the keys and the column of the duplicate rule. */
  pure def differ(st: Store, s: Row, t: Row): bool =
    s.payload != t.payload or (st == Journal and s.created != t.created) or (st == Snapshot and s.deleted != t.deleted)

  /** `merge`: keep the row under the target key; delete the copy under the stored key. */
  pure def mergeTx(st: Store, t: Table, c: Cand, src: Row, tgt: Row): Table = {
    val kept =
      if (st == Journal) { ...tgt, deleted: tgt.deleted or src.deleted }          // DeletedFlag
      else if (src.created > tgt.created) src                                     // LatestCreated
      else tgt
    t.remove(c.src).put(c.dst, kept)
  }

  /**
   * One attempt of moveOne with no other writer: read the source; send the move (Put if absent +
   * Delete if unchanged); when it is refused, read the target: the same row -> compare and merge,
   * another row -> Blocked. When the source is gone (an earlier attempt whose response was lost):
   * the target holds the row -> Moved (Merged when merging), else Gone.
   */
  pure def attempt(big: bool, st: Store, t: Table, c: Cand, merging: bool): Effect =
    if (t.has(c.src) and isRowOf(t.get(c.src), c)) {
      val src = t.get(c.src)
      if (not(t.has(c.dst))) {
        if (tooBig(big, c)) { table: t, outcome: Failed("rejected by DynamoDB") }
        else { table: t.remove(c.src).put(c.dst, src), outcome: Moved }
      } else {
        val tgt = t.get(c.dst)
        if (isRowOf(tgt, c)) {
          if (differ(st, src, tgt)) { table: t, outcome: Conflict }
          else { table: mergeTx(st, t, c, src, tgt), outcome: Merged }
        } else { table: t, outcome: Blocked(lrow(tgt)) }
      }
    } else {
      if (t.has(c.dst) and isRowOf(t.get(c.dst), c)) { table: t, outcome: if (merging) Merged else Moved }
      else { table: t, outcome: Gone }
    }

  /** The attempt wrote the table (the effect whose response can be lost). */
  pure def writes(e: Effect): bool = match e.outcome { | Moved => true | Merged => true | _ => false }

  /** `settle`: after the last attempt, read both keys. */
  pure def settleOutcome(t: Table, c: Cand, merging: bool): Outcome = {
    val source = t.has(c.src) and isRowOf(t.get(c.src), c)
    val target = t.has(c.dst) and isRowOf(t.get(c.dst), c)
    if (not(source) and target) { if (merging) Merged else Moved }
    else if (source) Failed("gave up")
    else Failed("outcome unknown")
  }

  pure def isRemaining(o: Outcome): bool =
    match o { | Blocked(_) => true | Conflict => true | Failed(_) => true | _ => false }

  /**
   * The rule for a target whose winner did not reach it (review C-F1 / O-F6): when the winner's outcome leaves the
   * target without its row (Failed, for every reason, and Gone), the target stays reserved for it for the rest of the
   * run, and the other candidates stay pending (remaining). Up to edfce815 the target went to the next candidate.
   */
  pure def reserves(o: Outcome): bool =
    match o { | Failed(_) => true | Gone => true | _ => false }

  // ---------------------------------------------------------------------------------------------
  // The reference: a whole run without stops, lost responses, or transient failures
  // ---------------------------------------------------------------------------------------------

  type RunResult = { table: Table, pending: Set[Cand], remaining: Set[Cand], reserved: Set[Key] }

  pure def roundStep(big: bool, st: Store, s: RunResult): RunResult =
    if (s.pending == Set()) s
    else {
      val ready = readyOf(s.pending, s.reserved)
      if (ready == Set()) { ...s, pending: Set(), remaining: s.remaining.union(s.pending) }
      else ready.fold({ ...s, pending: s.pending.exclude(ready) }, (acc, c) => {
        val e = attempt(big, st, acc.table, c, false)
        {
          ...acc, table: e.table,
          remaining: if (isRemaining(e.outcome)) acc.remaining.union(Set(c)) else acc.remaining,
          reserved: if (reserves(e.outcome)) acc.reserved.union(Set(c.dst)) else acc.reserved,
        }
      })
    }

  /** KeyMigration.run --execute from table `t`, to the end; `big` as `rejectShared`. */
  pure def fullRunWith(big: bool, st: Store, t: Table, p: Params): RunResult =
    1.to(LROWS.size() + 2).fold({ table: t, pending: scanCandidates(t, p), remaining: Set(), reserved: Set() }, (s, _) => roundStep(big, st, s))

  /** The reference without the rejection under h130 (the pure tests use it). */
  pure def fullRun(st: Store, t: Table, p: Params): RunResult = fullRunWith(false, st, t, p)

  // ---------------------------------------------------------------------------------------------
  // --verify: KeyVerification.run, as one atomic look
  // ---------------------------------------------------------------------------------------------

  pure def sharedGroupsPresent(t: Table): bool = {
    val ids = presentRows(t).map(l => l._1).filter(id => GROUP.has(id))
    ids.exists(a => ids.exists(b => a != b and GROUP.get(a) == GROUP.get(b)))
  }

  pure def verifyPass(t: Table, idx: Table, dir: Dir, form: Form): bool = and {
    t.keys().forall(k => isInPlace(placement(dir, form, lrow(t.get(k)), k))),  // toMove == 0, notMovable == 0
    idx == t,                                                                 // indexMismatch empty
    dir == Reverse implies not(sharedGroupsPresent(t)),                       // sharedKeyGroups empty
  }

  // ---------------------------------------------------------------------------------------------
  // The initial tables
  // ---------------------------------------------------------------------------------------------

  /**
   * The keys a row of `l` is under in the initial table, by choice `n`:
   * 0 none, 1 the 1.3.0 key, 2 the new key, 3 both (two copies), 4 the #831 key or the unknown key,
   * 5 the #831 key and the 1.3.0 key. A choice that `l` cannot have falls back to the 1.3.0 key, or
   * to the new key when 1.3.0 had none.
   */
  pure def keysOf(l: LRow, n: int): Set[Key] = {
    val k130 = if (R130.has(l)) R130.get(l) else PLAIN.get(l)
    val alt = if (K831.has(l)) Set(K831.get(l)) else if (UNKNOWN.has(l)) Set(UNKNOWN.get(l)) else Set(k130)
    if (n == 0) Set()
    else if (n == 1) Set(k130)
    else if (n == 2) Set(PLAIN.get(l))
    else if (n == 3) Set(k130, PLAIN.get(l))
    else if (n == 4) alt
    else if (K831.has(l)) Set(K831.get(l), k130)
    else Set(k130)
  }

  /** A table of 1.3.0 alone: rows under their 1.3.0 key or the #831 key, one copy each. */
  pure def keysOf130(l: LRow, n: int): Set[Key] =
    if (not(R130.has(l)) or n == 0) Set()
    else if (n == 4 and K831.has(l)) Set(K831.get(l))
    else Set(R130.get(l))

  /** The rows that cannot reach their new key: the cycle, the row too large, the row it blocks, the unknown key. */
  pure val STUCK: Set[LRow] = Set(("c", 1), ("d", 1), ("e", 1), ("f", 1), ("u", 1))

  pure def keysFor(mode: int, l: LRow, n: int): Set[Key] =
    if (mode == 1) keysOf130(l, n)
    else if (mode == 2) (if (STUCK.contains(l)) Set() else keysOf(l, n))
    else keysOf(l, n)

  /** Rows are put in a fixed order; a key that already holds a row keeps it (the overwrite of <= 1.2.0 lost the other). */
  pure def buildTable(st: Store, keyChoice: LRow -> Set[Key], second: LRow -> int, del: LRow -> bool, cre: LRow -> int): Table =
    LROWS.fold(Map(), (t, l) => {
      val ks = keyChoice.get(l)
      ks.fold(t, (acc, k) => {
        if (acc.has(k)) acc
        else {
          // the copy under the new key may differ from the copy under another key
          val isSecond = ks.size() == 2 and k == PLAIN.get(l)
          val row = {
            id: l._1, seq: l._2,
            payload: if (isSecond) second.get(l) else 1,
            deleted: if (st == Journal) (if (isSecond) del.get(l) else not(del.get(l))) else false,
            created: if (st == Snapshot) (if (isSecond) cre.get(l) else 3 - cre.get(l)) else 0,
          }
          acc.put(k, row)
        }
      })
    })

  // ---------------------------------------------------------------------------------------------
  // State
  // ---------------------------------------------------------------------------------------------

  var tables: Store -> Table
  var idx: Store -> Table            // the persistence-id index (eventually consistent)
  var tools: Store -> Tool
  var init130: Store -> bool         // the initial table is one of 1.3.0 alone
  var initTables: Store -> Table     // the initial table (history, read by the invariants)
  /** The table when the first of the runs with the same params, since the last finished run, started. */
  var epochStart: Store -> Table
  /** The params and the result of the last finished run. */
  var lastFinished: Store -> { done: bool, params: Params, clean: bool }
  /** The last --verify: its params and whether it matched, and the table it saw. */
  var lastVerify: Store -> { done: bool, dir: Dir, form: Form, pass: bool, table: Table }
  var budget: int                    // stops, lost responses, and transient failures per trace
  /** DynamoDB rejects the --reverse move of h to h130 (`BIG_SHARED`). Fixed per trace; false in `init`. */
  var rejectShared: bool

  pure val ALL_PARAMS_SEL: Set[Set[Id]] = Set(IDS).union(IDS.map(id => Set(id)))
  pure val DIRS: Set[Dir] = Set(Forward, Reverse)
  pure val FORMS: Set[Form] = Set(FPlain, F130)

  pure val IDLE_TOOL: Tool = {
    phase: Idle, params: { dir: Forward, form: FPlain, sel: IDS }, pending: Set(), round: Set(),
    attempts: Map(), merging: Set(), outcomes: Set(), remaining: Set(), unmovable: Set(),
    gaveUp: false, epochGaveUp: false, reserved: Set(), startTable: Map(),
  }

  action init: bool = {
    // 0: any table; 1: a table of 1.3.0 alone; 2: a table whose every row can move
    nondet modeJ = 0.to(2).oneOf()
    nondet modeS = 0.to(2).oneOf()
    val pure130J = modeJ == 1
    val pure130S = modeS == 1
    nondet chJ = LROWS.setOfMaps(0.to(6)).oneOf()
    nondet chS = LROWS.setOfMaps(0.to(6)).oneOf()
    nondet secondJ = LROWS.setOfMaps(Set(1, 2)).oneOf()
    nondet secondS = LROWS.setOfMaps(Set(1, 2)).oneOf()
    nondet delJ = LROWS.setOfMaps(Set(true, false)).oneOf()
    nondet creS = LROWS.setOfMaps(Set(1, 2)).oneOf()
    val keysJ = LROWS.mapBy(l => keysFor(modeJ, l, chJ.get(l)))
    val keysS = LROWS.mapBy(l => keysFor(modeS, l, chS.get(l)))
    val tj = buildTable(Journal, keysJ, if (modeJ == 2) LROWS.mapBy(_ => 1) else secondJ, delJ, creS)
    val ts = buildTable(Snapshot, keysS, if (modeS == 2) LROWS.mapBy(_ => 1) else secondS, delJ, creS)
    val t0 = Map(Journal -> tj, Snapshot -> ts)
    all {
      tables' = t0,
      idx' = t0,
      tools' = STORES.mapBy(_ => IDLE_TOOL),
      init130' = Map(Journal -> pure130J, Snapshot -> pure130S),
      initTables' = t0,
      epochStart' = t0,
      lastFinished' = STORES.mapBy(_ => { done: false, params: IDLE_TOOL.params, clean: true }),
      lastVerify' = STORES.mapBy(_ => { done: false, dir: Forward, form: FPlain, pass: false, table: Map() }),
      budget' = 6,
      // the rejection under h130 only in initSharedPair and the scenario tests: `progress` and `roundTrip`
      // take a start from which h can go back
      rejectShared' = false,
    }
  }

  /** A given start (for the scenario tests): the same journal and snapshot table, the index caught up. */
  action initWith(t: Table, pure130: bool): bool = initWithRule(t, pure130, false)

  action initWithRule(t: Table, pure130: bool, big: bool): bool = all {
    tables' = STORES.mapBy(_ => t),
    idx' = STORES.mapBy(_ => t),
    tools' = STORES.mapBy(_ => IDLE_TOOL),
    init130' = STORES.mapBy(_ => pure130),
    initTables' = STORES.mapBy(_ => t),
    epochStart' = STORES.mapBy(_ => t),
    lastFinished' = STORES.mapBy(_ => { done: false, params: IDLE_TOOL.params, clean: true }),
    lastVerify' = STORES.mapBy(_ => { done: false, dir: Forward, form: FPlain, pass: false, table: Map() }),
    budget' = 6,
    rejectShared' = big,
  }

  /**
   * A start weighted towards review C-F1 (`quint run --init initSharedPair`): the pair h, i that shares h130,
   * under their new keys, beside rows that move directly (b) and in order (a after b). The random steps still
   * choose the direction, the faults, and the stops.
   */
  pure val SHARED_PAIR_START: Table = Map(
    "hP" -> { id: "h", seq: 1, payload: 1, deleted: false, created: 0 },
    "iP" -> { id: "i", seq: 1, payload: 1, deleted: false, created: 0 },
    "a130" -> { id: "a", seq: 1, payload: 1, deleted: false, created: 0 },
    "b130" -> { id: "b", seq: 1, payload: 1, deleted: false, created: 0 },
  )
  action initSharedPair: bool = {
    // one trace in four has DynamoDB reject h under h130
    nondet big = 0.to(3).oneOf()
    initWithRule(SHARED_PAIR_START, false, big == 0)
  }

  // ---------------------------------------------------------------------------------------------
  // Actions
  // ---------------------------------------------------------------------------------------------

  /** Start `--execute --writers-stopped` (after a stop, the process starts again from the scan). */
  action startRun(st: Store, p: Params): bool = {
    val tool = tools.get(st)
    val t = tables.get(st)
    // A new epoch unless this run continues the runs with the same params that stopped, or that finished but gave
    // up (the convergence is judged from the start of those runs; review C-F1: a run that gave up must not reset it).
    val continues = tool.params == p and tool.startTable != Map() and (tool.phase == Idle or (tool.phase == Finished and tool.gaveUp))
    all {
      tool.phase != Running,
      epochStart' = epochStart.set(st, if (continues) epochStart.get(st) else t),
      tools' = tools.set(st, {
        ...IDLE_TOOL, phase: Running, params: p, pending: scanCandidates(t, p),
        unmovable: scanUnmovable(t, p), startTable: t,
        epochGaveUp: continues and (tool.epochGaveUp or tool.gaveUp),
      }),
      tables' = tables, idx' = idx, init130' = init130, initTables' = initTables,
      lastFinished' = lastFinished, lastVerify' = lastVerify, budget' = budget, rejectShared' = rejectShared,
    }
  }

  /** The start of a round of `execute`, or its end when no pending row is ready. */
  action nextRound(st: Store): bool = {
    val tool = tools.get(st)
    val ready = readyOf(tool.pending, tool.reserved)
    // findCycles: what is left waits for a pending row; all of it is remaining.
    val fin = { ...tool, phase: Finished, pending: Set(), remaining: tool.remaining.union(tool.pending) }
    all {
      tool.phase == Running,
      tool.round == Set(),
      tools' = tools.set(st, if (ready != Set()) { ...tool, round: ready, pending: tool.pending.exclude(ready) } else fin),
      lastFinished' =
        if (ready != Set()) lastFinished
        else lastFinished.set(st, { done: true, params: tool.params, clean: not(tool.gaveUp) }),
      tables' = tables, idx' = idx, init130' = init130, initTables' = initTables,
      epochStart' = epochStart, lastVerify' = lastVerify, budget' = budget, rejectShared' = rejectShared,
    }
  }

  pure def record(tool: Tool, c: Cand, o: Outcome): Tool = {
    ...tool,
    round: tool.round.exclude(Set(c)),
    outcomes: tool.outcomes.union(Set((c, o))),
    remaining: if (isRemaining(o)) tool.remaining.union(Set(c)) else tool.remaining,
    gaveUp: tool.gaveUp or o == Failed("gave up") or o == Failed("outcome unknown"),
    reserved: if (reserves(o)) tool.reserved.union(Set(c.dst)) else tool.reserved,
  }

  /** An attempt of moveOne whose response arrives. */
  action attemptOk(st: Store, c: Cand): bool = {
    val tool = tools.get(st)
    val e = attempt(rejectShared, st, tables.get(st), c, tool.merging.contains(c))
    all {
      tool.phase == Running,
      tool.round.contains(c),
      tables' = tables.set(st, e.table),
      tools' = tools.set(st, record(tool, c, e.outcome)),
      idx' = idx, init130' = init130, initTables' = initTables,
      epochStart' = epochStart, lastFinished' = lastFinished, lastVerify' = lastVerify, budget' = budget, rejectShared' = rejectShared,
    }
  }

  /** The transaction of an attempt is applied, but its response is lost: the tool tries again. */
  action attemptLost(st: Store, c: Cand): bool = {
    val tool = tools.get(st)
    val e = attempt(rejectShared, st, tables.get(st), c, tool.merging.contains(c))
    val n = if (tool.attempts.has(c)) tool.attempts.get(c) else 0
    all {
      budget > 0,
      tool.phase == Running,
      tool.round.contains(c),
      writes(e),
      n < MAX_ATTEMPTS,
      tables' = tables.set(st, e.table),
      tools' = tools.set(st, {
        ...tool,
        attempts: tool.attempts.put(c, n + 1),
        merging: if (e.outcome == Merged) tool.merging.union(Set(c)) else tool.merging,
      }),
      budget' = budget - 1, rejectShared' = rejectShared,
      idx' = idx, init130' = init130, initTables' = initTables,
      epochStart' = epochStart, lastFinished' = lastFinished, lastVerify' = lastVerify,
    }
  }

  /** An attempt that fails without writing (throttling, a lost connection before the request). */
  action attemptTransient(st: Store, c: Cand): bool = {
    val tool = tools.get(st)
    val n = if (tool.attempts.has(c)) tool.attempts.get(c) else 0
    all {
      budget > 0,
      tool.phase == Running,
      tool.round.contains(c),
      n < MAX_ATTEMPTS,
      tools' = tools.set(st, { ...tool, attempts: tool.attempts.put(c, n + 1) }),
      budget' = budget - 1, rejectShared' = rejectShared,
      tables' = tables, idx' = idx, init130' = init130, initTables' = initTables,
      epochStart' = epochStart, lastFinished' = lastFinished, lastVerify' = lastVerify,
    }
  }

  /** After the last attempt: `settle` reads both keys. */
  action settle(st: Store, c: Cand): bool = {
    val tool = tools.get(st)
    all {
      tool.phase == Running,
      tool.round.contains(c),
      tool.attempts.has(c) and tool.attempts.get(c) >= MAX_ATTEMPTS,
      tools' = tools.set(st, record(tool, c, settleOutcome(tables.get(st), c, tool.merging.contains(c)))),
      tables' = tables, idx' = idx, init130' = init130, initTables' = initTables,
      epochStart' = epochStart, lastFinished' = lastFinished, lastVerify' = lastVerify, budget' = budget, rejectShared' = rejectShared,
    }
  }

  /** The process stops (killed, the machine goes down). The table keeps what the transactions did. */
  action stop(st: Store): bool = all {
    budget > 0,
    tools.get(st).phase == Running,
    tools' = tools.set(st, { ...tools.get(st), phase: Idle }),
    budget' = budget - 1, rejectShared' = rejectShared,
    tables' = tables, idx' = idx, init130' = init130, initTables' = initTables,
    epochStart' = epochStart, lastFinished' = lastFinished, lastVerify' = lastVerify,
  }

  pure def lagging(t: Table, i: Table): Set[Key] =
    t.keys().union(i.keys()).filter(k => t.has(k) != i.has(k) or (t.has(k) and t.get(k) != i.get(k)))

  /** DynamoDB updates the index for key `k`. */
  action propagateKey(st: Store, k: Key): bool = {
    val t = tables.get(st)
    val i = idx.get(st)
    all {
      lagging(t, i).contains(k),
      idx' = idx.set(st, if (t.has(k)) i.put(k, t.get(k)) else i.remove(k)),
      tables' = tables, tools' = tools, init130' = init130, initTables' = initTables,
      epochStart' = epochStart, lastFinished' = lastFinished, lastVerify' = lastVerify, budget' = budget, rejectShared' = rejectShared,
    }
  }

  /** DynamoDB updates the index for one key, in any order. */
  action propagate(st: Store): bool = {
    val lag = lagging(tables.get(st), idx.get(st))
    nondet k = (if (lag == Set()) Set("") else lag).oneOf()
    propagateKey(st, k)
  }

  /** --verify (it moves nothing; it does not run while --execute runs on the same table). */
  action verify(st: Store, dir: Dir, form: Form): bool = all {
    tools.get(st).phase != Running,
    lastVerify' = lastVerify.set(st, {
      done: true, dir: dir, form: form,
      pass: verifyPass(tables.get(st), idx.get(st), dir, form), table: tables.get(st),
    }),
    tables' = tables, idx' = idx, tools' = tools, init130' = init130, initTables' = initTables,
    epochStart' = epochStart, lastFinished' = lastFinished, budget' = budget, rejectShared' = rejectShared,
  }

  pure val ALL_KEYS: Set[Key] = ORD.keys()

  pure val NO_CAND: Cand = { id: "", seq: 0, src: "", dst: "" }

  /**
   * One attempt for a candidate of the round. The code runs the round in parallel; its candidates touch
   * disjoint keys (see the design), so any order is one of the code's.
   */
  action moveStep(st: Store): bool = {
    val r = tools.get(st).round
    nondet c = (if (r == Set()) Set(NO_CAND) else r).oneOf()
    all {
      r != Set(),
      any { attemptOk(st, c), attemptOk(st, c), attemptLost(st, c), attemptTransient(st, c), settle(st, c) },
    }
  }

  action toolStep(st: Store): bool = {
    val tool = tools.get(st)
    nondet dir = DIRS.oneOf()
    nondet form = FORMS.oneOf()
    nondet sel = ALL_PARAMS_SEL.oneOf()
    any {
      startRun(st, { dir: dir, form: form, sel: IDS }),
      startRun(st, { dir: dir, form: form, sel: sel }),
      nextRound(st),
      moveStep(st),
      moveStep(st),
      stop(st),
    }
  }

  action step = {
    nondet st = STORES.oneOf()
    nondet dir = DIRS.oneOf()
    nondet form = FORMS.oneOf()
    any {
      toolStep(st),
      toolStep(st),
      toolStep(st),
      propagate(st),
      verify(st, dir, form),
    }
  }

  // ---------------------------------------------------------------------------------------------
  // Invariants
  // ---------------------------------------------------------------------------------------------

  /** INV1 No row is lost: every logical row of the initial table has a copy in the table. */
  val noLoss: bool = STORES.forall(st =>
    presentRows(initTables.get(st)).forall(l => copiesIn(tables.get(st), l).size() >= 1))

  /**
   * INV2 No row is duplicated: a logical row has at most as many copies as it had at the start, one
   * when it had one, and no row that the initial table did not hold appears.
   */
  val noDuplicate: bool = STORES.forall(st => {
    val t0 = initTables.get(st)
    val t = tables.get(st)
    presentRows(t).forall(l => {
      val n0 = copiesIn(t0, l).size()
      n0 >= 1 and copiesIn(t, l).size() <= n0
    })
  })

  /** INV2' A logical row that had one copy is, at every moment, under exactly one key. */
  val exactlyOnce: bool = STORES.forall(st =>
    presentRows(initTables.get(st)).forall(l =>
      copiesIn(initTables.get(st), l).size() == 1 implies copiesIn(tables.get(st), l).size() == 1))

  /**
   * INV3 Contents are kept: each copy holds the attributes of a copy of the start; a journal copy is
   * deleted only when a copy was; a snapshot copy has the `created` of a copy.
   */
  val contentsKept: bool = STORES.forall(st => {
    val t0 = initTables.get(st)
    val t = tables.get(st)
    t.keys().forall(k => {
      val r = t.get(k)
      val origins = copiesIn(t0, lrow(r)).map(k0 => t0.get(k0))
      and {
        origins.exists(o => o.payload == r.payload),
        r.deleted implies origins.exists(o => o.deleted),
        origins.exists(o => o.created == r.created),
      }
    })
  })

  /** INV4 A copy is only ever under a key of its own forms, or the key it started under. */
  val keysKnown: bool = STORES.forall(st => {
    val t = tables.get(st)
    t.keys().forall(k => knownKeys(lrow(t.get(k))).union(copiesIn(initTables.get(st), lrow(t.get(k)))).contains(k))
  })

  /** INV5 A row under a key of no known form is never moved or changed. */
  val unknownStays: bool = STORES.forall(st => {
    val t0 = initTables.get(st)
    val t = tables.get(st)
    t0.keys().filter(k => UNKNOWN.has(lrow(t0.get(k))) and UNKNOWN.get(lrow(t0.get(k))) == k)
      .forall(k => t.has(k) and t.get(k) == t0.get(k))
  })

  /** INV6 A run changes only the rows of the selected persistence ids (--persistence-id). */
  val selectionRespected: bool = STORES.forall(st => {
    val tool = tools.get(st)
    val t = tables.get(st)
    val t0 = tool.startTable
    tool.phase != Idle implies and {
      t0.keys().filter(k => not(tool.params.sel.contains(t0.get(k).id))).forall(k => t.has(k) and t.get(k) == t0.get(k)),
      t.keys().filter(k => not(tool.params.sel.contains(t.get(k).id))).forall(k => t0.has(k) and t0.get(k) == t.get(k)),
    }
  })

  /** INV7 A finished run leaves each selected row under the key it belongs under, or lists it. */
  val finishedReported: bool = STORES.forall(st => {
    val tool = tools.get(st)
    val t = tables.get(st)
    val listed = tool.remaining.map(c => c.src).union(tool.unmovable)
    tool.phase == Finished implies
      t.keys().filter(k => tool.params.sel.contains(t.get(k).id)).forall(k =>
        isInPlace(placement(tool.params.dir, tool.params.form, lrow(t.get(k)), k)) or listed.contains(k))
  })

  /**
   * INV8 Convergence: however often the runs with the same params stopped, lost a response,
   * failed for a while, or finished after giving up, the run that finishes without giving up ends in
   * the table that one uninterrupted run from the start of those runs gives.
   *
   * The runs that gave up (the set G = finished runs with tool.gaveUp) are not dropped: the epoch
   * (epochStart) does not restart after them, so the clean run after G is compared with the start of
   * the first run; and G itself is checked by `finishedWithinReference`, every state by
   * `withinReference` (review C-F1: the old exclusion hid the counterexample).
   */
  val converges: bool = STORES.forall(st => {
    val tool = tools.get(st)
    (tool.phase == Finished and not(tool.gaveUp)) implies
      tables.get(st) == fullRunWith(rejectShared, st, epochStart.get(st), tool.params).table
  })

  /** The copies of `l` with their keys and contents. */
  pure def rowsOf(t: Table, l: LRow): Set[(Key, Row)] = copiesIn(t, l).map(k => (k, t.get(k)))

  /**
   * INV8a No row goes elsewhere (no exclusion, every state): since the start of the epoch, each
   * logical row is as it was at the start, or as the uninterrupted run from the start leaves it.
   * A row never takes a key that the uninterrupted run does not give it (C-F1: the loser of a shared
   * target took it after its winner gave up).
   */
  val withinReference: bool = STORES.forall(st => {
    val tool = tools.get(st)
    val t0 = epochStart.get(st)
    val t = tables.get(st)
    tool.startTable != Map() implies {
      val ref = fullRunWith(rejectShared, st, t0, tool.params).table
      presentRows(t0).union(presentRows(t)).forall(l => rowsOf(t, l) == rowsOf(t0, l) or rowsOf(t, l) == rowsOf(ref, l))
    }
  })

  /**
   * INV8b Every finished run, also one that gave up (no exclusion): each logical row is as the
   * uninterrupted run leaves it, or it is as at the start of the epoch and listed (remaining or not
   * movable). A run that gave up stops short; it never goes elsewhere.
   */
  val finishedWithinReference: bool = STORES.forall(st => {
    val tool = tools.get(st)
    val t0 = epochStart.get(st)
    val t = tables.get(st)
    val listed = tool.remaining.map(c => c.src).union(tool.unmovable)
    tool.phase == Finished implies {
      val ref = fullRunWith(rejectShared, st, t0, tool.params).table
      presentRows(t0).union(presentRows(t)).forall(l =>
        rowsOf(t, l) == rowsOf(ref, l) or (rowsOf(t, l) == rowsOf(t0, l) and copiesIn(t, l).exists(k => listed.contains(k))))
    }
  })

  /**
   * INV3' Versions are kept (review O-F1): every content (payload) that a logical row had at the
   * start is still held by one of its copies. A merge of copies with different contents breaks it.
   */
  val versionsKept: bool = STORES.forall(st => {
    val t0 = initTables.get(st)
    val t = tables.get(st)
    presentRows(t0).forall(l =>
      copiesIn(t0, l).map(k => t0.get(k).payload).forall(p => copiesIn(t, l).exists(k => t.get(k).payload == p)))
  })

  /**
   * A start from which every selected row can reach its place: no row of the stuck set (cycle,
   * too large, blocked by it, unknown key), no row that the plan cannot move, copies with equal
   * contents, and for --reverse no shared-key pair with both ids present.
   */
  pure def movableStart(st: Store, t0: Table, p: Params): bool = and {
    t0.keys().forall(k => match placement(p.dir, p.form, lrow(t0.get(k)), k) { | Unmovable(_) => false | _ => true }),
    presentRows(t0).forall(l => not(STUCK.contains(l))),
    presentRows(t0).forall(l => copiesIn(t0, l).forall(k1 => copiesIn(t0, l).forall(k2 => not(differ(st, t0.get(k1), t0.get(k2)))))),
    p.dir == Reverse implies not(sharedGroupsPresent(t0)),
  }

  /**
   * INV7' Rows that can move do move (review O-F2): a clean finished run over all ids, from a start
   * that `movableStart` accepts, leaves nothing remaining and every row in place. Outside the
   * premise, `finishedReported` and `finishedWithinReference` hold; the runs that gave up are G.
   */
  val progress: bool = STORES.forall(st => {
    val tool = tools.get(st)
    val t = tables.get(st)
    (tool.phase == Finished and not(tool.gaveUp) and tool.params.sel == IDS and movableStart(st, epochStart.get(st), tool.params))
      implies (tool.remaining == Set() and t.keys().forall(k => isInPlace(placement(tool.params.dir, tool.params.form, lrow(t.get(k)), k))))
  })

  /** INV9 Idempotence: a second uninterrupted run moves nothing (for every params, from the current table). */
  val idempotent: bool = STORES.forall(st => DIRS.forall(dir => FORMS.forall(form => {
    val p = { dir: dir, form: form, sel: IDS }
    val once = fullRunWith(rejectShared, st, tables.get(st), p).table
    fullRunWith(rejectShared, st, once, p).table == once
  })))

  /** The table that a full reverse run gives a table of 1.3.0 alone: every row under its 1.3.0 key. */
  pure def norm130(t: Table): Table =
    t.keys().fold(Map(), (acc, k) => acc.put(R130.get(lrow(t.get(k))), t.get(k)))

  /**
   * INV10 Round trip: from a table of 1.3.0 alone, any forward runs (stopped or finished, any
   * selection, either configured form) followed by a finished full --reverse run give back the table
   * of 1.3.0. A row of #831 comes back under the 1.3.0 key, the key that both formats give it.
   */
  val roundTrip: bool = STORES.forall(st => {
    val lf = lastFinished.get(st)
    val tool = tools.get(st)
    (init130.get(st) and tool.phase == Finished and lf.done and lf.clean and lf.params.dir == Reverse and lf.params.sel == IDS)
      implies tables.get(st) == norm130(initTables.get(st))
  })

  /**
   * INV11 What a matching forward --verify with the PlainText resolvers says (legacy-keys-enabled =
   * false is safe): every logical row of the start is under its new key, once, with its contents, and
   * the index shows the table.
   */
  val verifySound: bool = STORES.forall(st => {
    val v = lastVerify.get(st)
    (v.done and v.pass and v.dir == Forward and v.form == FPlain) implies
      presentRows(initTables.get(st)).forall(l => copiesIn(v.table, l) == Set(PLAIN.get(l)))
  })

  /** INV12 With --reverse, --verify does not match while two ids of a shared-key group hold rows. */
  val verifyReverseShared: bool = STORES.forall(st => {
    val v = lastVerify.get(st)
    (v.done and v.pass and v.dir == Reverse) implies not(sharedGroupsPresent(v.table))
  })

  /** INV13 A row that 1.3.0 cannot hold (j) never goes back: no reverse run moves it. */
  val unreversibleStays: bool = STORES.forall(st => {
    val tool = tools.get(st)
    tool.outcomes.forall(co => co._1.id == "j" implies tool.params.dir == Forward)
  })

  val safety: bool = and {
    noLoss, noDuplicate, exactlyOnce, contentsKept, keysKnown, unknownStays, selectionRespected,
    finishedReported, converges, roundTrip, verifySound, verifyReverseShared, unreversibleStays,
    withinReference, finishedWithinReference, versionsKept, progress,
  }

  /** The invariants that do not compute the reference run (cheap per state). */
  val cheapSafety: bool = and {
    noLoss, noDuplicate, exactlyOnce, contentsKept, keysKnown, unknownStays, selectionRespected,
    finishedReported, roundTrip, verifySound, verifyReverseShared, unreversibleStays, versionsKept,
  }

  // ---------------------------------------------------------------------------------------------
  // Witnesses (reachability of each case; `quint run --witnesses`)
  // ---------------------------------------------------------------------------------------------

  pure def outcomeSeen(tool: Tool, f: Outcome => bool): bool = tool.outcomes.exists(co => f(co._2))
  val wMoved: bool = STORES.exists(st => outcomeSeen(tools.get(st), o => o == Moved))
  val wMerged: bool = STORES.exists(st => outcomeSeen(tools.get(st), o => o == Merged))
  val wBlocked: bool = STORES.exists(st => outcomeSeen(tools.get(st), o => match o { | Blocked(_) => true | _ => false }))
  val wConflict: bool = STORES.exists(st => outcomeSeen(tools.get(st), o => o == Conflict))
  val wRejected: bool = STORES.exists(st => outcomeSeen(tools.get(st), o => o == Failed("rejected by DynamoDB")))
  val wGaveUp: bool = STORES.exists(st => tools.get(st).gaveUp)
  /** The run has two candidates for the target `k` (both of the shared pair are selected and need it). */
  pure def sharedInRun(tool: Tool, k: Key): bool =
    scanCandidates(tool.startTable, tool.params).filter(c => c.dst == k).size() >= 2

  /** C-F1: a reverse run gave up on the winner of the shared target h130, with the other candidate in the run. */
  val wGaveUpShared: bool = STORES.exists(st => and {
    tools.get(st).params.dir == Reverse,
    tools.get(st).outcomes.exists(co => co._1.dst == "h130" and co._2 == Failed("gave up")),
    sharedInRun(tools.get(st), "h130"),
  })
  /** R3: DynamoDB rejected the winner of the shared target h130, with the other candidate in the run. */
  val wRejectedShared: bool = STORES.exists(st => and {
    tools.get(st).outcomes.exists(co => co._1.dst == "h130" and co._2 == Failed("rejected by DynamoDB")),
    sharedInRun(tools.get(st), "h130"),
  })
  /** The clean run after a run that gave up, in the same epoch (review R6: `epochGaveUp`, not the start table). */
  val wCleanAfterGaveUp: bool = STORES.exists(st => tools.get(st).phase == Finished and not(tools.get(st).gaveUp) and tools.get(st).epochGaveUp)
  val wProgress: bool = STORES.exists(st => tools.get(st).phase == Finished and not(tools.get(st).gaveUp) and tools.get(st).params.sel == IDS and movableStart(st, epochStart.get(st), tools.get(st).params))
  val wMergedAfterLost: bool = STORES.exists(st => tools.get(st).merging.exists(c => tools.get(st).outcomes.contains((c, Merged))))
  val wCycleRemaining: bool = STORES.exists(st => tools.get(st).phase == Finished and tools.get(st).remaining.exists(c => c.id == "c"))
  val wFinishedForward: bool = STORES.exists(st => tools.get(st).phase == Finished and tools.get(st).params.dir == Forward and tools.get(st).outcomes.size() >= 3)
  val wFinishedAfterStop: bool = STORES.exists(st => tools.get(st).phase == Finished and epochStart.get(st) != tools.get(st).startTable)
  val wRoundTrip: bool = STORES.exists(st => init130.get(st) and tools.get(st).phase == Finished and tools.get(st).params.dir == Reverse and tools.get(st).params.sel == IDS and epochStart.get(st) != initTables.get(st))
  val wVerifyForward: bool = STORES.exists(st => lastVerify.get(st).pass and lastVerify.get(st).dir == Forward and lastVerify.get(st).form == FPlain)
  val wVerifyReverse: bool = STORES.exists(st => lastVerify.get(st).pass and lastVerify.get(st).dir == Reverse)
  val wIndexLag: bool = STORES.exists(st => idx.get(st) != tables.get(st))
}
