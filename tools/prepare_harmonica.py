"""保留游戏口琴的起音及渐强，在成熟吹奏段选循环接缝；48 kHz 单声道，无损压缩。"""
from pathlib import Path
import argparse
import wave
import struct
import zlib
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
        start = 240000
        target = pcm[start:start + blend].astype(np.float64)
        # 避开起音和文件末尾的自然淡出，用同相、相近振幅的 32 ms 片段交叠。
        best = None
        for end in range(336000, min(len(pcm), 408000), 12):
            candidate = pcm[end - blend:end].astype(np.float64)
            error = float(np.mean((candidate - target) ** 2))
            if best is None or error < best[0]:
                best = (error, end)
        error, end = best
        body = pcm[:end]
        values = body.astype(np.int64)
        delta = np.diff(np.diff(values, prepend=0), prepend=0).astype("<i2")
        # 模 16 位的预测残差可以精确还原；zlib 自带完整性检查。
        packed = b"LXH1" + struct.pack("<I", end) + zlib.compress(delta.tobytes(), 9)
        decoded_delta = np.frombuffer(zlib.decompress(packed[8:]), dtype="<i2").astype(np.int64)
        restored = np.cumsum(np.cumsum(decoded_delta)).astype("<i2")
        assert np.array_equal(body, restored), filename
        (destination / f"{midi}.pcm").write_bytes(packed)
        rows.append(f"{midi}\t{end}\t{start}\t{end}\t{blend}\t1")
        scores.append(error ** .5 / 32768)
    (destination / "index.tsv").write_text("\n".join(rows) + "\n", encoding="utf-8")
    print(f"Prepared {len(rows)} samples; seam RMS error {min(scores):.4f}..{max(scores):.4f}; 48kHz mono")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("source", type=Path)
    parser.add_argument("destination", type=Path)
    args = parser.parse_args()
    prepare(args.source, args.destination)
