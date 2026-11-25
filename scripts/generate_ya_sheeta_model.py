#!/usr/bin/env python3
"""
Builds a compact, deterministic Int8-quantized binary acoustic keyword-spotting model
for the Egyptian Arabic wake phrase 'يا شيتا' (/jaː ˈʃiː.ta/).

Output: app/src/main/assets/models/cheetah_ya_sheeta_kws.bin (CHKW v1 format)
Zero runtime cloud dependency; uses the exact 16kHz 512-FFT / 24-Mel / 13-MFCC + ZCR +
spectral-tilt DSP pipeline implemented in AudioFeatureExtractor.kt.
"""

import math
import os
import random
import struct
import zlib

SAMPLE_RATE = 16000
FRAME_SIZE = 400
FRAME_HOP = 160
FFT_SIZE = 512
NUM_MEL = 24
NUM_MFCC = 13
TIME_STEPS = 24
STEP_FEATS = 16
SEGMENTS = 4
INPUT_DIM = SEGMENTS * STEP_FEATS  # 64
HIDDEN_DIM = 32
NUM_CLASSES = 2


def hz_to_mel(hz: float) -> float:
    return 2595.0 * math.log10(1.0 + hz / 700.0)


def mel_to_hz(mel: float) -> float:
    return 700.0 * (10.0 ** (mel / 2595.0) - 1.0)


HAMMING = [0.54 - 0.46 * math.cos(2.0 * math.pi * i / (FRAME_SIZE - 1)) for i in range(FRAME_SIZE)]


def build_mel_filterbank():
    low_mel = hz_to_mel(100.0)
    high_mel = hz_to_mel(6800.0)
    mel_pts = [low_mel + i * (high_mel - low_mel) / (NUM_MEL + 1) for i in range(NUM_MEL + 2)]
    bins = [max(0, min(FFT_SIZE // 2, int((FFT_SIZE + 1) * mel_to_hz(m) / SAMPLE_RATE))) for m in mel_pts]
    bank = []
    for m in range(NUM_MEL):
        l, c, r = bins[m], bins[m + 1], bins[m + 2]
        weights = []
        for k in range(l, r + 1):
            if k < c and c > l:
                w = (k - l) / float(c - l)
            elif k >= c and r > c:
                w = (r - k) / float(r - c)
            else:
                w = 0.0
            if w > 0.0:
                weights.append((k, w))
        bank.append(weights)
    return bank


MEL_BANK = build_mel_filterbank()
DCT_MAT = [
    [math.sqrt(2.0 / NUM_MEL) * math.cos(math.pi * c * (m + 0.5) / NUM_MEL) for m in range(NUM_MEL)]
    for c in range(NUM_MFCC)
]


def _build_fft_tables():
    n = FFT_SIZE
    bitrev = [0] * n
    j = 0
    for i in range(1, n):
        bit = n >> 1
        while j & bit:
            j ^= bit
            bit >>= 1
        j ^= bit
        bitrev[i] = j
    stages = []
    length = 2
    while length <= n:
        half = length // 2
        ang_step = -2.0 * math.pi / length
        twiddles = [(math.cos(ang_step * k), math.sin(ang_step * k)) for k in range(half)]
        stages.append((length, half, twiddles))
        length <<= 1
    return bitrev, stages


_FFT_BITREV, _FFT_STAGES = _build_fft_tables()


def fft_mag_sq(x):
    n = FFT_SIZE
    real = [x[_FFT_BITREV[i]] for i in range(n)]
    imag = [0.0] * n
    for length, half, twiddles in _FFT_STAGES:
        for i in range(0, n, length):
            for k in range(half):
                wr, wi = twiddles[k]
                idx1 = i + k
                idx2 = idx1 + half
                u_r, u_i = real[idx1], imag[idx1]
                r2, i2 = real[idx2], imag[idx2]
                v_r = r2 * wr - i2 * wi
                v_i = r2 * wi + i2 * wr
                real[idx1] = u_r + v_r
                imag[idx1] = u_i + v_i
                real[idx2] = u_r - v_r
                imag[idx2] = u_i - v_i
    inv_n = 1.0 / n
    return [(real[k] * real[k] + imag[k] * imag[k]) * inv_n for k in range(n // 2 + 1)]


def extract_tensor(pcm_floats, noise_floor=0.01):
    n = len(pcm_floats)
    if n < FRAME_SIZE * 2:
        return None
    pre = [pcm_floats[0]] + [pcm_floats[i] - 0.97 * pcm_floats[i - 1] for i in range(1, n)]
    gate = max(0.011, noise_floor * 1.6)
    raw_frames = []
    offset = 0
    low_bin_end = int(1200.0 * FFT_SIZE / SAMPLE_RATE)
    high_bin_start = int(2200.0 * FFT_SIZE / SAMPLE_RATE)
    high_bin_end = int(6800.0 * FFT_SIZE / SAMPLE_RATE)

    while offset + FRAME_SIZE <= n:
        rms = math.sqrt(sum(pcm_floats[offset + i] ** 2 for i in range(FRAME_SIZE)) / FRAME_SIZE)
        if rms >= gate:
            buf = [0.0] * FFT_SIZE
            zc = 0
            for i in range(FRAME_SIZE):
                buf[i] = pre[offset + i] * HAMMING[i]
                if i > 0 and ((pcm_floats[offset + i - 1] >= 0) != (pcm_floats[offset + i] >= 0)):
                    zc += 1
            ps = fft_mag_sq(buf)
            mel_log = []
            for m in range(NUM_MEL):
                s = 1e-7
                for k, w in MEL_BANK[m]:
                    s += ps[k] * w
                mel_log.append(math.log(s))
            mfcc = [sum(mel_log[m] * DCT_MAT[c][m] for m in range(NUM_MEL)) for c in range(NUM_MFCC)]
            low_e = sum(ps[2:low_bin_end]) + 1e-7
            high_e = sum(ps[high_bin_start:high_bin_end]) + 1e-7
            tilt = math.log(high_e / low_e)
            raw_frames.append((mfcc, rms, zc / float(FRAME_SIZE), tilt))
        offset += FRAME_HOP

    if len(raw_frames) < 10:
        return None

    # CMVN across MFCCs
    nf = float(len(raw_frames))
    mean = [sum(f[0][c] for f in raw_frames) / nf for c in range(NUM_MFCC)]
    std = [
        max(1e-4, math.sqrt(sum((f[0][c] - mean[c]) ** 2 for f in raw_frames) / nf))
        for c in range(NUM_MFCC)
    ]

    norm_frames = []
    for mfcc, rms, zcr, tilt in raw_frames:
        row = [(mfcc[c] - mean[c]) / std[c] for c in range(NUM_MFCC)]
        row.append(min(3.0, rms * 8.0))
        row.append(min(3.0, zcr * 6.0))
        row.append(max(-3.0, min(3.0, tilt * 0.45)))
        norm_frames.append(row)

    # Resample to TIME_STEPS = 24
    tensor = []
    for t in range(TIME_STEPS):
        idx = min(len(norm_frames) - 1, int((t + 0.5) * len(norm_frames) / TIME_STEPS))
        tensor.append(norm_frames[idx])
    return tensor


def tensor_to_segment_vec(tensor):
    # Pool 24x16 into 4 segments x 16 = 64 features
    seg_len = TIME_STEPS // SEGMENTS
    out = []
    for s in range(SEGMENTS):
        chunk = tensor[s * seg_len:(s + 1) * seg_len]
        for c in range(STEP_FEATS):
            out.append(sum(row[c] for row in chunk) / float(seg_len))
    return out


def synth_ya_sheeta(rng: random.Random, dur_scale=1.0, pitch_shift=1.0, noise_lvl=0.02):
    """
    Synthesizes realistic acoustic approximation of Egyptian Arabic 'يا شيتا' (/jaː ˈʃiː.ta/):
      Seg 1 (0.00 - 0.28): /jaː/  voiced glide + open vowel (F1~700Hz, F2~1300-1900Hz)
      Seg 2 (0.28 - 0.52): /ʃ/    voiceless postalveolar fricative (2.3k-5.8kHz noise, high ZCR)
      Seg 3 (0.52 - 0.78): /iː/   voiced high front vowel (F1~310Hz, F2~2250Hz)
      Seg 4 (0.78 - 1.00): /ta/   short /t/ burst + /a/ formant release
    """
    dur = rng.uniform(0.62, 0.96) * dur_scale
    n = int(SAMPLE_RATE * dur)
    f0 = rng.uniform(115.0, 210.0) * pitch_shift
    samples = []
    lp = 0.0
    for i in range(n):
        t = i / float(SAMPLE_RATE)
        u = i / float(n)
        val = 0.0
        if u < 0.28:
            # /ja:/ voiced glide into /a/
            p = u / 0.28
            f1 = 380.0 + 340.0 * p
            f2 = 2100.0 - 800.0 * p
            env = math.sin(math.pi * p) ** 0.5
            val = env * (
                0.45 * math.sin(2 * math.pi * f0 * t)
                + 0.35 * math.sin(2 * math.pi * f1 * t)
                + 0.22 * math.sin(2 * math.pi * f2 * t)
            )
        elif u < 0.52:
            # /ʃ/ postalveolar fricative (band-centered ~3.2kHz + 4.6kHz + broadband hiss)
            p = (u - 0.28) / 0.24
            env = math.sin(math.pi * p) ** 0.45
            hiss = rng.uniform(-1.0, 1.0)
            hp = hiss - 0.85 * lp
            lp = hiss
            fric = 0.5 * hp + 0.35 * math.sin(2 * math.pi * rng.uniform(2800, 3800) * t)
            val = env * 0.48 * fric
        elif u < 0.78:
            # /i:/ voiced high front vowel
            p = (u - 0.52) / 0.26
            env = math.sin(math.pi * p) ** 0.5
            val = env * (
                0.45 * math.sin(2 * math.pi * f0 * t)
                + 0.36 * math.sin(2 * math.pi * 310.0 * pitch_shift * t)
                + 0.26 * math.sin(2 * math.pi * 2250.0 * t)
            )
        else:
            # /t/ burst + /a/
            p = (u - 0.78) / 0.22
            if p < 0.22:
                val = 0.52 * (rng.uniform(-1.0, 1.0) * (1.0 - p / 0.22))
            else:
                env = math.sin(math.pi * ((p - 0.22) / 0.78)) ** 0.6
                val = env * (
                    0.42 * math.sin(2 * math.pi * f0 * t)
                    + 0.34 * math.sin(2 * math.pi * 720.0 * t)
                    + 0.22 * math.sin(2 * math.pi * 1250.0 * t)
                )
        val += rng.uniform(-noise_lvl, noise_lvl)
        samples.append(max(-0.98, min(0.98, val)))
    return samples


def synth_negative(rng: random.Random, kind: int):
    dur = rng.uniform(0.55, 1.05)
    n = int(SAMPLE_RATE * dur)
    samples = []
    if kind == 0:
        # Pure/multi-tone or hum
        f1 = rng.uniform(180.0, 2400.0)
        f2 = f1 * rng.uniform(1.3, 2.2)
        for i in range(n):
            t = i / float(SAMPLE_RATE)
            samples.append(0.35 * math.sin(2 * math.pi * f1 * t) + 0.2 * math.sin(2 * math.pi * f2 * t))
    elif kind == 1:
        # Broadband / babble noise
        for _ in range(n):
            samples.append(rng.uniform(-0.35, 0.35))
    elif kind == 2:
        # Non-matching speech-like utterance (vowels only / reverse order)
        f0 = rng.uniform(110.0, 220.0)
        for i in range(n):
            t = i / float(SAMPLE_RATE)
            u = i / float(n)
            f1 = 450.0 + 300.0 * math.sin(2 * math.pi * 1.5 * u)
            f2 = 1100.0 + 500.0 * math.cos(2 * math.pi * 2.0 * u)
            samples.append(0.4 * math.sin(2 * math.pi * f0 * t) + 0.3 * math.sin(2 * math.pi * f1 * t) + 0.2 * math.sin(2 * math.pi * f2 * t))
    else:
        # Fricative at wrong position (end instead of middle)
        f0 = rng.uniform(120.0, 200.0)
        for i in range(n):
            t = i / float(SAMPLE_RATE)
            u = i / float(n)
            if u > 0.65:
                samples.append(0.45 * rng.uniform(-1.0, 1.0))
            else:
                samples.append(0.45 * math.sin(2 * math.pi * f0 * t) + 0.3 * math.sin(2 * math.pi * 650.0 * t))
    return samples


def train_and_export():
    rng = random.Random(20261125)
    dataset = []
    pos_tensors = []

    for _ in range(80):
        pcm = synth_ya_sheeta(
            rng,
            dur_scale=rng.uniform(0.85, 1.18),
            pitch_shift=rng.uniform(0.85, 1.18),
            noise_lvl=rng.uniform(0.005, 0.045),
        )
        ten = extract_tensor(pcm)
        if ten is not None:
            pos_tensors.append(ten)
            dataset.append((tensor_to_segment_vec(ten), 1))

    for idx in range(120):
        pcm = synth_negative(rng, idx % 4)
        ten = extract_tensor(pcm)
        if ten is not None:
            dataset.append((tensor_to_segment_vec(ten), 0))

    # Canonical reference MFCC trajectory (24 x 13)
    ref_mfcc = [
        [sum(ten[t][c] for ten in pos_tensors) / float(len(pos_tensors)) for c in range(NUM_MFCC)]
        for t in range(TIME_STEPS)
    ]

    # Train 2-layer MLP (64 -> 32 -> 2) with Adam/SGD + L2 weight decay
    w1 = [[rng.gauss(0.0, 0.12) for _ in range(HIDDEN_DIM)] for _ in range(INPUT_DIM)]
    b1 = [0.0] * HIDDEN_DIM
    w2 = [[rng.gauss(0.0, 0.15) for _ in range(NUM_CLASSES)] for _ in range(HIDDEN_DIM)]
    b2 = [0.0] * NUM_CLASSES

    lr = 0.018
    l2 = 1e-4
    for epoch in range(55):
        rng.shuffle(dataset)
        for x, y in dataset:
            # Forward
            h = [0.0] * HIDDEN_DIM
            for j in range(HIDDEN_DIM):
                s = b1[j]
                for i in range(INPUT_DIM):
                    s += x[i] * w1[i][j]
                h[j] = s if s > 0.0 else 0.0
            logits = [b2[k] + sum(h[j] * w2[j][k] for j in range(HIDDEN_DIM)) for k in range(NUM_CLASSES)]
            m_log = max(logits)
            exps = [math.exp(v - m_log) for v in logits]
            sum_e = sum(exps)
            probs = [e / sum_e for e in exps]

            # Backward
            d_logits = [probs[0] - (1.0 if y == 0 else 0.0), probs[1] - (1.0 if y == 1 else 0.0)]
            d_h = [0.0] * HIDDEN_DIM
            for j in range(HIDDEN_DIM):
                if h[j] > 0.0:
                    d_h[j] = d_logits[0] * w2[j][0] + d_logits[1] * w2[j][1]
            for k in range(NUM_CLASSES):
                b2[k] -= lr * d_logits[k]
                for j in range(HIDDEN_DIM):
                    w2[j][k] -= lr * (h[j] * d_logits[k] + l2 * w2[j][k])
            for j in range(HIDDEN_DIM):
                if h[j] > 0.0:
                    b1[j] -= lr * d_h[j]
                    for i in range(INPUT_DIM):
                        w1[i][j] -= lr * (x[i] * d_h[j] + l2 * w1[i][j])
        lr *= 0.985

    # Quantize weights to symmetric Int8
    def quantize_matrix(mat):
        flat = [v for row in mat for v in row]
        max_abs = max(1e-6, max(abs(v) for v in flat))
        scale = max_abs / 127.0
        q = [max(-127, min(127, int(round(v / scale)))) for v in flat]
        return q, scale

    q_w1, w1_scale = quantize_matrix(w1)
    q_w2, w2_scale = quantize_matrix(w2)
    q_ref, ref_scale = quantize_matrix(ref_mfcc)

    # Evaluate quantized accuracy
    correct = 0
    for x, y in dataset:
        h = []
        for j in range(HIDDEN_DIM):
            s = b1[j]
            for i in range(INPUT_DIM):
                s += x[i] * (q_w1[i * HIDDEN_DIM + j] * w1_scale)
            h.append(s if s > 0.0 else 0.0)
        logits = [
            b2[k] + sum(h[j] * (q_w2[j * NUM_CLASSES + k] * w2_scale) for j in range(HIDDEN_DIM))
            for k in range(NUM_CLASSES)
        ]
        pred = 1 if logits[1] > logits[0] else 0
        if pred == y:
            correct += 1
    acc = correct / float(len(dataset))

    # Pack CHKW v1 binary
    header = struct.pack(
        "<4sHHBBHHHffff",
        b"CHKW",
        1,              # version
        SAMPLE_RATE,    # 16000
        TIME_STEPS,     # 24
        NUM_MFCC,       # 13
        INPUT_DIM,      # 64
        HIDDEN_DIM,     # 32
        NUM_CLASSES,    # 2
        0.64,           # recommended threshold
        w1_scale,
        w2_scale,
        ref_scale,
    )
    payload = bytearray()
    payload.extend(header)
    payload.extend(struct.pack(f"<{len(q_w1)}b", *q_w1))
    payload.extend(struct.pack(f"<{HIDDEN_DIM}f", *b1))
    payload.extend(struct.pack(f"<{len(q_w2)}b", *q_w2))
    payload.extend(struct.pack(f"<{NUM_CLASSES}f", *b2))
    payload.extend(struct.pack(f"<{len(q_ref)}b", *q_ref))
    crc = zlib.crc32(payload) & 0xFFFFFFFF
    payload.extend(struct.pack("<I", crc))

    out_path = os.path.join("app", "src", "main", "assets", "models", "cheetah_ya_sheeta_kws.bin")
    os.makedirs(os.path.dirname(out_path), exist_ok=True)
    with open(out_path, "wb") as f:
        f.write(payload)

    print(f"Generated {out_path}: {len(payload)} bytes, quantized accuracy={acc:.4f}, crc32=0x{crc:08x}")


if __name__ == "__main__":
    train_and_export()
