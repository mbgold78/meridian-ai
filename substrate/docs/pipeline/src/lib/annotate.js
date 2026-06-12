import { PNG } from 'pngjs';

/**
 * Numbered-callout compositor (plan §3 `annotations`): draws a white-ringed
 * red disc with the callout number at each anchor-resolved position, onto a
 * copy of the base PNG. Deliberately PURE JS (pngjs + an embedded 5x7 digit
 * bitmap) — no native canvas/sharp, no system font dependency — so the
 * annotated bytes are deterministic on every platform. (The plan suggested
 * sharp/canvas; both rasterize text through platform font stacks, which is
 * exactly the nondeterminism this pipeline exists to kill. Recorded as a
 * deviation in the S1 commit.)
 *
 * The base PNG stays the regression artifact; the annotated PNG is written
 * alongside as `<name>.annotated.png` and BOTH are hashed into the manifest.
 */

const DISC_RGB = [220, 38, 38]; // red-600 — high contrast on the dark theme
const RING_RGB = [255, 255, 255];
const DIGIT_RGB = [255, 255, 255];

// Classic 5x7 bitmap digits.
const FONT = {
  0: ['01110', '10001', '10011', '10101', '11001', '10001', '01110'],
  1: ['00100', '01100', '00100', '00100', '00100', '00100', '01110'],
  2: ['01110', '10001', '00001', '00010', '00100', '01000', '11111'],
  3: ['11111', '00010', '00100', '00010', '00001', '10001', '01110'],
  4: ['00010', '00110', '01010', '10010', '11111', '00010', '00010'],
  5: ['11111', '10000', '11110', '00001', '00001', '10001', '01110'],
  6: ['00110', '01000', '10000', '11110', '10001', '10001', '01110'],
  7: ['11111', '00001', '00010', '00100', '01000', '01000', '01000'],
  8: ['01110', '10001', '10001', '01110', '10001', '10001', '01110'],
  9: ['01110', '10001', '10001', '01111', '00001', '00010', '01100'],
};
const GLYPH_W = 5;
const GLYPH_H = 7;
const SCALE = 2;

function blendPixel(png, x, y, [r, g, b], coverage) {
  if (coverage <= 0 || x < 0 || y < 0 || x >= png.width || y >= png.height) return;
  const a = Math.min(1, coverage);
  const i = (png.width * y + x) << 2;
  png.data[i] = Math.round(r * a + png.data[i] * (1 - a));
  png.data[i + 1] = Math.round(g * a + png.data[i + 1] * (1 - a));
  png.data[i + 2] = Math.round(b * a + png.data[i + 2] * (1 - a));
  png.data[i + 3] = Math.max(png.data[i + 3], Math.round(255 * a));
}

/** Filled disc with 1px analytic anti-aliased edge. */
function drawDisc(png, cx, cy, radius, rgb) {
  const r = radius;
  for (let y = Math.floor(cy - r - 1); y <= Math.ceil(cy + r + 1); y++) {
    for (let x = Math.floor(cx - r - 1); x <= Math.ceil(cx + r + 1); x++) {
      const d = Math.hypot(x - cx, y - cy);
      blendPixel(png, x, y, rgb, r + 0.5 - d);
    }
  }
}

function drawNumber(png, cx, cy, text) {
  const w = text.length * GLYPH_W * SCALE + (text.length - 1) * SCALE;
  const h = GLYPH_H * SCALE;
  let ox = Math.round(cx - w / 2);
  const oy = Math.round(cy - h / 2);
  for (const ch of text) {
    const glyph = FONT[ch];
    if (!glyph) continue;
    for (let gy = 0; gy < GLYPH_H; gy++) {
      for (let gx = 0; gx < GLYPH_W; gx++) {
        if (glyph[gy][gx] === '1') {
          for (let sy = 0; sy < SCALE; sy++) {
            for (let sx = 0; sx < SCALE; sx++) {
              blendPixel(png, ox + gx * SCALE + sx, oy + gy * SCALE + sy, DIGIT_RGB, 1);
            }
          }
        }
      }
    }
    ox += (GLYPH_W + 1) * SCALE;
  }
}

/**
 * @param {Buffer} baseBuffer  the captured PNG
 * @param {Array<{n:number, cx:number, cy:number}>} callouts capture-time-resolved positions
 * @returns {Buffer} annotated PNG bytes
 */
export function annotatePng(baseBuffer, callouts) {
  const png = PNG.sync.read(baseBuffer);
  for (const c of callouts) {
    const label = String(c.n);
    const radius = label.length > 1 ? 14 : 11;
    const cx = Math.round(c.cx);
    const cy = Math.round(c.cy);
    if (cx < -radius || cy < -radius || cx > png.width + radius || cy > png.height + radius) {
      // A silent off-canvas callout is a lying artifact — the guide would
      // claim a numbered marker that no reader can see.
      throw new Error(
        `annotation n=${c.n} lands outside the captured image (${cx},${cy} vs ${png.width}x${png.height}) — enlarge the spec viewport or re-anchor`,
      );
    }
    drawDisc(png, cx, cy, radius + 2, RING_RGB);
    drawDisc(png, cx, cy, radius, DISC_RGB);
    drawNumber(png, cx, cy, label);
  }
  return PNG.sync.write(png);
}
