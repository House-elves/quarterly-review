#!/usr/bin/env bash
# Installs quarterly-review for Elf Desk: the sources under ~/.local/share/quarterly-review, a wrapper on the PATH,
# and a config (taken from weekly-status's when there is one). No timer: you write a review from the desk.
set -euo pipefail

CONFIG_DIR="$HOME/.config/quarterly-review"
INSTALL_DIR="$HOME/.local/share/quarterly-review"
BIN_DIR="$HOME/.local/bin"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

for tool in java jbang gh claude; do
    command -v "$tool" &>/dev/null || { echo "Missing $tool"; exit 1; }
done

mkdir -p "$CONFIG_DIR" "$INSTALL_DIR" "$BIN_DIR"
if [[ ! -f "$CONFIG_DIR/config" ]]; then
    weekly="$HOME/.config/weekly-status/config"
    if [[ -f "$weekly" ]]; then
        grep -E '^(GITHUB_USER|DISPLAY_NAME|EXCLUDE_ORGS)=' "$weekly" > "$CONFIG_DIR/config"
    else
        read -rp "Your GitHub username: " user
        read -rp "Your name: " name
        read -rp "GitHub orgs to leave out (comma-separated): " orgs
        printf 'GITHUB_USER=%s\nDISPLAY_NAME=%s\nEXCLUDE_ORGS=%s\n' "$user" "$name" "$orgs" > "$CONFIG_DIR/config"
    fi
    chmod 600 "$CONFIG_DIR/config"
    echo "Config written to $CONFIG_DIR/config"
fi

cp "$SCRIPT_DIR"/*.java "$INSTALL_DIR/"
JBANG_BIN=$(dirname "$(command -v jbang)")
JAVA_BIN=$(dirname "$(command -v java)")
cat > "$BIN_DIR/quarterly-review" <<WRAPPER
#!/usr/bin/env bash
export PATH="$JBANG_BIN:$JAVA_BIN:$BIN_DIR:\$PATH"
exec jbang "$INSTALL_DIR/QuarterlyReview.java" "\$@"
WRAPPER
chmod +x "$BIN_DIR/quarterly-review"
echo "Installed: $BIN_DIR/quarterly-review"
