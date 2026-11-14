#!/usr/bin/env python3
"""Собирает справочник производителей по MAC для Android-приложения из реестров IEEE.

Скачать (сайт IEEE отклоняет запросы без браузерного User-Agent):
  https://standards-oui.ieee.org/oui/oui.csv      (MA-L, префикс 24 бита)
  https://standards-oui.ieee.org/oui28/mam.csv    (MA-M, 28 бит)
  https://standards-oui.ieee.org/oui36/oui36.csv  (MA-S, 36 бит)
Запуск: python3 tools/build_oui.py oui.csv mam.csv oui36.csv
Результат: android/app/src/main/assets/oui.dat (gzip; расширение не .gz — иначе сборка Android распакует файл и переименует) — строки «имена», затем «префикс<TAB>номер имени».
"""
import csv, gzip, os, re, sys

LEGAL = re.compile(
    r"[\s,.]+(inc|incorporated|corp|corporation|co|company|ltd|limited|llc|l\.l\.c|gmbh|ag|s\.?a|sas|b\.?v|ab|oy|"
    r"s\.?p\.?a|s\.?r\.?l|s\.?r\.?o|pte|pty|plc|k\.?k|ulc|lp|se|n\.?v|kg|co\.?,?\s*ltd|co\.?,?\s*limited)\.?$",
    re.IGNORECASE,
)

def clean(name: str) -> str:
    name = " ".join(name.replace('"', "").split())
    prev = None
    while prev != name:
        prev = name
        name = LEGAL.sub("", name).strip(" ,.&-")
    return name

def main(paths):
    entries = {}
    for path in paths:
        with open(path, newline="", encoding="utf-8") as f:
            for row in csv.DictReader(f):
                prefix = row["Assignment"].strip().upper()
                name = clean(row["Organization Name"])
                if not re.fullmatch(r"[0-9A-F]{6,9}", prefix) or not name or name.lower() == "private":
                    continue
                entries[prefix] = name
    # Одна компания в реестре пишется по-разному («HUAWEI TECHNOLOGIES», «Huawei Technologies»): берём самое
    # частое написание, и не заглавными буквами, если такое есть.
    from collections import Counter
    counts = Counter(entries.values())
    best = {}
    for n, c in counts.items():
        key = n.lower()
        cur = best.get(key)
        if cur is None or (n != n.upper(), c) > (cur != cur.upper(), counts[cur]):
            best[key] = n
    entries = {p: best[n.lower()] for p, n in entries.items()}
    names = sorted(set(entries.values()))
    index = {n: i for i, n in enumerate(names)}
    out = os.path.join(os.path.dirname(__file__), "..", "android/app/src/main/assets/oui.dat")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    with gzip.open(out, "wt", encoding="utf-8", compresslevel=9) as g:
        g.write(f"{len(names)}\n")
        g.writelines(n + "\n" for n in names)
        for p in sorted(entries, key=lambda p: (len(p), p)):
            g.write(f"{p}\t{index[entries[p]]}\n")
    print(f"{len(entries)} префиксов, {len(names)} имён -> {os.path.getsize(out)} байт")

if __name__ == "__main__":
    main(sys.argv[1:])
