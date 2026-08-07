#!/data/data/com.termux/files/usr/bin/bash
set -e
pkg update -y
pkg install -y python
python -m pip install -r requirements.txt
echo
echo "Gotowe."
echo "1) skopiuj .env.example do .env"
echo "2) wpisz OPENAI_API_KEY"
echo "3) uruchom: bash termux_start.sh"
