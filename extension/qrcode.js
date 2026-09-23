/* qrcode.js — tiny byte-mode QR encoder, no dependencies.
 *
 * Drawn in two places that must agree: the extension popup and the broker's
 * /pair page, both of which encode the very same `crremote://pair?...` deep link
 * the app already parses. Keeping it as one vendored file is what lets the
 * broker stay "stdlib + websockets", works with no network, and sidesteps MV3's
 * CSP, which forbids remote scripts outright.
 *
 * Implements ISO/IEC 18004 for byte mode only: version pick, Reed-Solomon,
 * block interleaving, the eight masks and their penalty scoring. Versions 1-20,
 * which is far more than a pairing link needs.
 */
(function (root) {
  "use strict";

  const MAX_VERSION = 20;
  const ECL = { L: 0, M: 1, Q: 2, H: 3 };
  const ECL_FORMAT_BITS = [1, 0, 3, 2];   // the spec's odd ordering, by ECL index

  // Tables from ISO/IEC 18004, indexed [ecl][version]; index 0 is a placeholder.
  const ECC_PER_BLOCK = [
    [0, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28],
    [0, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26],
    [0, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30],
    [0, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28],
  ];
  const NUM_BLOCKS = [
    [0, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8],
    [0, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16],
    [0, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20],
    [0, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25],
  ];

  const bit = (x, i) => ((x >>> i) & 1) !== 0;

  /** Module count of a version, minus nothing: the raw grid is size x size. */
  const sizeOf = (ver) => ver * 4 + 17;

  /** Data+ECC modules available, i.e. everything but the function patterns. */
  function rawDataModules(ver) {
    let n = (16 * ver + 128) * ver + 64;
    if (ver >= 2) {
      const align = Math.floor(ver / 7) + 2;
      n -= (25 * align - 10) * align - 55;
      if (ver >= 7) n -= 36;
    }
    return n;
  }

  function dataCodewords(ver, ecl) {
    return Math.floor(rawDataModules(ver) / 8)
      - ECC_PER_BLOCK[ecl][ver] * NUM_BLOCKS[ecl][ver];
  }

  function alignmentPositions(ver) {
    if (ver === 1) return [];
    const n = Math.floor(ver / 7) + 2;
    const step = Math.ceil((ver * 4 + 4) / (n * 2 - 2)) * 2;
    const pos = [6];
    for (let p = sizeOf(ver) - 7; pos.length < n; p -= step) pos.splice(1, 0, p);
    return pos;
  }

  // ── GF(256) arithmetic for Reed-Solomon ─────────────────────────────────────
  function gfMul(a, b) {
    let z = 0;
    for (let i = 7; i >= 0; i--) {
      z = (z << 1) ^ ((z >>> 7) * 0x11d);
      z ^= ((b >>> i) & 1) * a;
    }
    return z & 0xff;
  }

  function rsDivisor(degree) {
    const coefs = new Uint8Array(degree);
    coefs[degree - 1] = 1;
    let root = 1;
    for (let i = 0; i < degree; i++) {
      for (let j = 0; j < degree; j++) {
        coefs[j] = gfMul(coefs[j], root);
        if (j + 1 < degree) coefs[j] ^= coefs[j + 1];
      }
      root = gfMul(root, 2);
    }
    return coefs;
  }

  function rsRemainder(data, divisor) {
    const out = new Uint8Array(divisor.length);
    for (const b of data) {
      const factor = b ^ out[0];
      out.copyWithin(0, 1);
      out[out.length - 1] = 0;
      for (let i = 0; i < divisor.length; i++) out[i] ^= gfMul(divisor[i], factor);
    }
    return out;
  }

  // ── encoding ────────────────────────────────────────────────────────────────
  function pickVersion(byteLen, ecl) {
    for (let ver = 1; ver <= MAX_VERSION; ver++) {
      const cc = ver < 10 ? 8 : 16;                 // byte-mode char-count bits
      if (4 + cc + byteLen * 8 <= dataCodewords(ver, ecl) * 8) return ver;
    }
    throw new Error("data too long for a QR code (" + byteLen + " bytes)");
  }

  /** Mode + length + payload + terminator + padding, as a codeword array. */
  function dataCodewordsFor(bytes, ver, ecl) {
    const capacity = dataCodewords(ver, ecl);
    const bits = [];
    const push = (val, len) => {
      for (let i = len - 1; i >= 0; i--) bits.push((val >>> i) & 1);
    };
    push(4, 4);                                     // byte mode
    push(bytes.length, ver < 10 ? 8 : 16);
    for (const b of bytes) push(b, 8);
    push(0, Math.min(4, capacity * 8 - bits.length));
    push(0, (8 - (bits.length % 8)) % 8);
    const out = new Uint8Array(capacity);
    for (let i = 0; i < bits.length; i++) out[i >>> 3] |= bits[i] << (7 - (i & 7));
    for (let i = bits.length / 8, pad = 0xec; i < capacity; i++, pad ^= 0xec ^ 0x11) {
      out[i] = pad;
    }
    return out;
  }

  /** Split into blocks, append ECC to each, interleave as the spec requires. */
  function interleave(data, ver, ecl) {
    const numBlocks = NUM_BLOCKS[ecl][ver];
    const eccLen = ECC_PER_BLOCK[ecl][ver];
    const rawCodewords = Math.floor(rawDataModules(ver) / 8);
    const shortBlocks = numBlocks - (rawCodewords % numBlocks);
    const shortLen = Math.floor(rawCodewords / numBlocks) - eccLen;
    const divisor = rsDivisor(eccLen);

    const blocks = [];
    for (let i = 0, off = 0; i < numBlocks; i++) {
      const len = shortLen + (i < shortBlocks ? 0 : 1);
      const dat = data.slice(off, off + len);
      off += len;
      blocks.push({ dat, ecc: rsRemainder(dat, divisor) });
    }

    const out = [];
    for (let i = 0; i < shortLen + 1; i++) {
      for (const b of blocks) if (i < b.dat.length) out.push(b.dat[i]);
    }
    for (let i = 0; i < eccLen; i++) for (const b of blocks) out.push(b.ecc[i]);
    return Uint8Array.from(out);
  }

  // ── the grid ────────────────────────────────────────────────────────────────
  function Grid(ver, ecl) {
    const size = sizeOf(ver);
    const mods = [], fn = [];
    for (let y = 0; y < size; y++) {
      mods.push(new Uint8Array(size));
      fn.push(new Uint8Array(size));
    }
    const set = (x, y, dark) => {
      mods[y][x] = dark ? 1 : 0;
      fn[y][x] = 1;
    };

    function finder(cx, cy) {
      for (let dy = -4; dy <= 4; dy++) {
        for (let dx = -4; dx <= 4; dx++) {
          const x = cx + dx, y = cy + dy;
          const d = Math.max(Math.abs(dx), Math.abs(dy));
          if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, d !== 2 && d !== 4);
        }
      }
    }

    function formatBits(mask) {
      const data = (ECL_FORMAT_BITS[ecl] << 3) | mask;
      let rem = data;
      for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
      const bits = ((data << 10) | rem) ^ 0x5412;
      for (let i = 0; i <= 5; i++) set(8, i, bit(bits, i));
      set(8, 7, bit(bits, 6));
      set(8, 8, bit(bits, 7));
      set(7, 8, bit(bits, 8));
      for (let i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
      for (let i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
      for (let i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
      set(8, size - 8, true);                       // the always-dark module
    }

    function versionBits() {
      if (ver < 7) return;
      let rem = ver;
      for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1f25);
      const bits = (ver << 12) | rem;
      for (let i = 0; i < 18; i++) {
        const b = bit(bits, i);
        const a = size - 11 + (i % 3), c = Math.floor(i / 3);
        set(a, c, b);
        set(c, a, b);
      }
    }

    // Function patterns first: they are what the data has to flow around.
    for (let i = 0; i < size; i++) {
      set(6, i, i % 2 === 0);
      set(i, 6, i % 2 === 0);
    }
    finder(3, 3);
    finder(size - 4, 3);
    finder(3, size - 4);
    const pos = alignmentPositions(ver);
    for (let i = 0; i < pos.length; i++) {
      for (let j = 0; j < pos.length; j++) {
        const corner = (i === 0 && j === 0) || (i === 0 && j === pos.length - 1)
          || (i === pos.length - 1 && j === 0);
        if (corner) continue;                       // finder patterns sit there
        for (let dy = -2; dy <= 2; dy++) {
          for (let dx = -2; dx <= 2; dx++) {
            set(pos[i] + dx, pos[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
          }
        }
      }
    }
    formatBits(0);                                  // placeholder, redrawn below
    versionBits();

    return { size, mods, fn, formatBits };
  }

  function drawCodewords(g, codewords) {
    const { size, mods, fn } = g;
    let i = 0;
    for (let right = size - 1; right >= 1; right -= 2) {
      if (right === 6) right = 5;                   // the vertical timing column
      for (let vert = 0; vert < size; vert++) {
        for (let j = 0; j < 2; j++) {
          const x = right - j;
          const upward = ((right + 1) & 2) === 0;
          const y = upward ? size - 1 - vert : vert;
          if (!fn[y][x] && i < codewords.length * 8) {
            mods[y][x] = bit(codewords[i >>> 3], 7 - (i & 7)) ? 1 : 0;
            i++;
          }
        }
      }
    }
  }

  const MASKS = [
    (x, y) => (x + y) % 2 === 0,
    (x, y) => y % 2 === 0,
    (x) => x % 3 === 0,
    (x, y) => (x + y) % 3 === 0,
    (x, y) => (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0,
    (x, y) => ((x * y) % 2) + ((x * y) % 3) === 0,
    (x, y) => (((x * y) % 2) + ((x * y) % 3)) % 2 === 0,
    (x, y) => (((x + y) % 2) + ((x * y) % 3)) % 2 === 0,
  ];

  function applyMask(g, mask) {
    const { size, mods, fn } = g;
    for (let y = 0; y < size; y++) {
      for (let x = 0; x < size; x++) {
        if (!fn[y][x] && MASKS[mask](x, y)) mods[y][x] ^= 1;
      }
    }
  }

  /** The spec's four penalty rules; the lowest-scoring mask is the one we keep. */
  function penalty(g) {
    const { size, mods } = g;
    let score = 0;

    const runs = (get) => {
      let run = 1;
      for (let i = 1; i <= size; i++) {
        if (i < size && get(i) === get(i - 1)) {
          run++;
        } else {
          if (run >= 5) score += 3 + (run - 5);
          run = 1;
        }
      }
    };
    for (let y = 0; y < size; y++) runs((i) => mods[y][i]);
    for (let x = 0; x < size; x++) runs((i) => mods[i][x]);

    for (let y = 0; y < size - 1; y++) {
      for (let x = 0; x < size - 1; x++) {
        const c = mods[y][x];
        if (c === mods[y][x + 1] && c === mods[y + 1][x] && c === mods[y + 1][x + 1]) {
          score += 3;
        }
      }
    }

    // Finder-lookalikes: 1:1:3:1:1 with four light modules on either side.
    const FINDER = "10111010000", FINDER_R = "00001011101";
    const count = (s) => {
      let n = 0;
      for (let i = s.indexOf(FINDER); i >= 0; i = s.indexOf(FINDER, i + 1)) n++;
      for (let i = s.indexOf(FINDER_R); i >= 0; i = s.indexOf(FINDER_R, i + 1)) n++;
      return n;
    };
    for (let y = 0; y < size; y++) score += 40 * count(mods[y].join(""));
    for (let x = 0; x < size; x++) {
      let col = "";
      for (let y = 0; y < size; y++) col += mods[y][x];
      score += 40 * count(col);
    }

    let dark = 0;
    for (let y = 0; y < size; y++) for (let x = 0; x < size; x++) dark += mods[y][x];
    const pct = (dark * 100) / (size * size);
    score += Math.floor(Math.abs(pct - 50) / 5) * 10;
    return score;
  }

  /** Encode `text` and return { version, size, modules[y][x] as 0/1 }. */
  function encode(text, eclName) {
    const ecl = ECL[(eclName || "M").toUpperCase()];
    if (ecl === undefined) throw new Error("unknown error-correction level");
    const bytes = new TextEncoder().encode(String(text));
    const ver = pickVersion(bytes.length, ecl);
    const codewords = interleave(dataCodewordsFor(bytes, ver, ecl), ver, ecl);

    const g = Grid(ver, ecl);
    drawCodewords(g, codewords);

    let best = -1, bestScore = Infinity;
    for (let mask = 0; mask < 8; mask++) {
      applyMask(g, mask);
      g.formatBits(mask);
      const s = penalty(g);
      if (s < bestScore) { bestScore = s; best = mask; }
      applyMask(g, mask);                           // masking is its own inverse
    }
    applyMask(g, best);
    g.formatBits(best);
    return { version: ver, size: g.size, modules: g.mods };
  }

  /**
   * An <svg> string. Always black on white with a real quiet zone: a QR inverted
   * to suit a dark theme is a QR many scanners refuse.
   */
  function svg(text, opts) {
    const o = opts || {};
    const qr = encode(text, o.ecl || "M");
    const quiet = o.quiet == null ? 4 : o.quiet;
    const dim = qr.size + quiet * 2;
    let path = "";
    for (let y = 0; y < qr.size; y++) {
      for (let x = 0; x < qr.size; x++) {
        if (qr.modules[y][x]) path += `M${x + quiet},${y + quiet}h1v1h-1z`;
      }
    }
    const size = o.size ? ` width="${o.size}" height="${o.size}"` : "";
    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${dim} ${dim}"${size}`
      + ` shape-rendering="crispEdges" role="img" aria-label="${o.label || "QR code"}">`
      + `<rect width="${dim}" height="${dim}" fill="#fff"/>`
      + `<path d="${path}" fill="#000"/></svg>`;
  }

  const api = { encode, svg };
  if (typeof module !== "undefined" && module.exports) module.exports = api;
  root.QR = api;
})(typeof self !== "undefined" ? self : globalThis);
