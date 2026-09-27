"""Prepare the game's local WAVs for the practice sampler (NumPy; no network).

Keep the attack, choose a correlated sustain seam between 2 and 4 seconds,
and retain a 32 ms linear overlap. Output is 48 kHz mono signed little-endian PCM.
"""
from pathlib import Path
import argparse
import wave
import numpy as np


def prepare(source: Path, destination: Path):
    destination.mkdir(parents=True, exist_ok=True)
    names = ["C", "Cs", "D", "Ds", "E", "F", "Fs", "G", "Gs", "A", "As", "B"]
    rows = []
    scores = []
    for midi in range(48, 86):
        filename = f"Harmonica_{names[midi % 12]}{midi // 12}.wav"
        with wave.open(str(source / filename)) as wav:
            assert (wav.getframerate(), wav.getnchannels(), wav.getsampwidth()) == (48000, 1, 2)
            pcm = np.frombuffer(wav.readframes(wav.getnframes()), dtype="<i2").copy()
        audible = np.flatnonzero(np.abs(pcm.astype(np.int32)) > 32768 * .018)
        assert len(audible), filename
        pcm = pcm[max(0, int(audible[0]) - 96):]
        blend = 1536
        start = 96000
        target = pcm[start:start + blend].astype(np.float64)
        # Pick a seam with similar phase and amplitude, rather than looping the tail/silence.
        best = None
        for end in range(144000, min(len(pcm), 192000), 12):
            candidate = pcm[end - blend:end].astype(np.float64)
            error = float(np.mean((candidate - target) ** 2))
            if best is None or error < best[0]:
                best = (error, end)
        error, end = best
        (destination / f"{midi}.pcm").write_bytes(pcm[:end].tobytes())
        rows.append(f"{midi}\t{end}\t{start}\t{end}\t{blend}")
        scores.append(error ** .5 / 32768)
    (destination / "index.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")
    print(f"Prepared {len(rows)} samples; seam RMS error {min(scores):.4f}..{max(scores):.4f}; 48kHz mono")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    prepare(args.source, args.destination)
