#!/usr/bin/env bash
# Plays Sipher for real on one Minecraft version, without a screen: a dedicated server of one loader with a Fabric and a
# NeoForge player on it, both talking. Checks that:
#   1. the server starts, Simple Voice Chat loads Sipher's plugin and Sipher writes its server settings;
#   2. both players join, each with a recording played as its microphone (-PdebugAudio), and each recognises it
#      ("ask not what your country can do for you");
#   3. the server relays each player's captions to the other (-PdebugRelay logs it), each player receives the other's,
#      and nothing goes wrong drawing them: the transcript, and a bubble above the other player, whom a test datapack
#      keeps them looking at. Logs and screenshots end up in the report directory.
#
# Usage: tools/runtime-test/run.sh <minecraft>-<server loader>      for example 1.21.1-fabric
#
# Needs Linux with xvfb-run (as in CI) or gamescope. The server plays in build/runtime-test/<node>/server, the players
# in build/runtime-test/<minecraft>-<loader>/client, the report goes to build/runtime-test/report/<node>/. Starting the
# test server accepts Minecraft's EULA (https://aka.ms/MinecraftEULA), which the project owner agreed to for these tests.
set -uo pipefail

node="${1:?usage: tools/runtime-test/run.sh <minecraft>-<server loader>}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
cd "$root" || exit 2
minecraft="${node%-*}"
games="build/runtime-test"
server="$games/$node/server"
report="$games/report/$node"
port=25599 # not the default, to keep clear of a server someone is playing on
recording="core/src/test/resources/audio/jfk.wav"
expected="ask not what your country can do for you"
loaders=(fabric neoforge)
declare -A players=([fabric]=FabricPlayer [neoforge]=NeoForgePlayer) launchers=()

if ! command -v xvfb-run > /dev/null && ! command -v gamescope > /dev/null; then
    echo "Needs xvfb-run or gamescope to run the players without a screen." >&2
    exit 2
fi

client() { echo "$games/$minecraft-$1/client"; }
rm -rf "${games:?}/$node" "$(client fabric)" "$(client neoforge)" "$report"
mkdir -p "$server" "$report"
failures=()
fail() {
    failures+=("$1")
    echo "FAIL: $1"
}

# The Gradle daemon starts the games, not the gradlew process, so find them by their working directory.
stop_game() {
    for pid in $(pgrep java); do
        if [ "$(readlink "/proc/$pid/cwd")" = "$root/$1" ]; then kill "$pid"; fi
    done
}

# wait_for <file> <regex> <seconds> <pid> <output>: until the file matches, the time runs out, the process ends or its
# output (Gradle's console, which carries the game's) has been silent for 5 minutes, as when the game is stuck.
wait_for() {
    local size=-1 quiet=0
    for ((i = 0; i < $3; i++)); do
        if grep -qiE "$2" "$1" 2>/dev/null; then return 0; fi
        if ! kill -0 "$4" 2>/dev/null; then return 1; fi
        if [ "$(stat -c %s "$5" 2>/dev/null)" = "$size" ]; then
            if ((++quiet >= 300)); then return 1; fi
        else
            size=$(stat -c %s "$5" 2>/dev/null)
            quiet=0
        fi
        sleep 1
    done
    return 1
}

# set_property <file> <key> <value>
set_property() {
    if grep -q "^$2=" "$1"; then sed -i "s/^$2=.*/$2=$3/" "$1"; else echo "$2=$3" >> "$1"; fi
}

gradle_args=(-PrunsDir="$games" --console=plain)

echo "== $node: first start of the dedicated server"
printf '# Accepted for the project owner, for Sipher'"'"'s automated tests (https://aka.ms/MinecraftEULA)\neula=true\n' \
    > "$server/eula.txt"
./gradlew ":$node:runServer" "${gradle_args[@]}" > "$report/server-first-start-gradle.log" 2>&1 &
launcher=$!
if wait_for "$report/server-first-start-gradle.log" 'Done \(' 1800 "$launcher" "$report/server-first-start-gradle.log"; then
    grep -q "Registering events for 'sipher'" "$report/server-first-start-gradle.log" \
        || fail "server: Simple Voice Chat did not load Sipher's plugin"
    [ -f "$server/world/serverconfig/sipher-server.toml" ] || fail "server: Sipher did not write world/serverconfig/sipher-server.toml"
else
    fail "server: did not start (see server-first-start-gradle.log)"
fi
stop_game "$server"
wait "$launcher" 2>/dev/null
cp "$server/logs/latest.log" "$report/server-first-start.log" 2>/dev/null

if [ ! -f "$server/server.properties" ] || [ ! -d "$server/world" ]; then
    echo "❌ **$node**: $(IFS=';'; echo "${failures[*]}")"
    [ -n "${GITHUB_STEP_SUMMARY:-}" ] && echo "- ❌ **$node**: the server did not start" >> "$GITHUB_STEP_SUMMARY"
    exit 1
fi

echo "== $node: server with a Fabric and a NeoForge player"
# Test players without Mojang accounts, in a world where nothing attacks them while they talk.
for setting in online-mode=false white-list=false enforce-secure-profile=false difficulty=peaceful "server-port=$port"; do
    set_property "$server/server.properties" "${setting%%=*}" "${setting#*=}"
done
# Puts the players on a platform in the open, 8 blocks apart, and keeps them looking at each other, to see the bubbles.
# The pack format covers every Minecraft version: pack_format/supported_formats before 1.21.9, min_format/max_format
# since.
pack="$server/world/datapacks/sipher-runtime-test"
mkdir -p "$pack/data/sipher_test/function" "$pack/data/minecraft/tags/function"
printf '{"pack": {"description": "Sipher runtime test", "pack_format": 48, "supported_formats": [48, 81], "min_format": 48, "max_format": 1000}}\n' \
    > "$pack/pack.mcmeta"
printf '{"values": ["sipher_test:tick"]}\n' > "$pack/data/minecraft/tags/function/tick.json"
printf '%s\n' \
    'execute as @a[tag=!sipher_test] unless entity @a[tag=sipher_test] at @s run fill ~-9 ~24 ~-9 ~9 ~24 ~9 minecraft:white_concrete' \
    'execute as @a[tag=!sipher_test] unless entity @a[tag=sipher_test] at @s run tp @s ~ ~25 ~' \
    'execute as @a[tag=!sipher_test] at @a[tag=sipher_test,limit=1] run tp @s ~8 ~ ~' \
    'tag @a add sipher_test' \
    'execute as @a at @s anchored eyes facing entity @a[distance=0.5..,sort=nearest,limit=1] eyes run tp @s ~ ~ ~ ~ ~' \
    > "$pack/data/sipher_test/function/tick.mcfunction"
./gradlew ":$node:runServer" "${gradle_args[@]}" -PdebugRelay > "$report/server-gradle.log" 2>&1 &
server_launcher=$!
wait_for "$report/server-gradle.log" 'Done \(' 1800 "$server_launcher" "$report/server-gradle.log" \
    || fail "server: did not start again (see server-gradle.log)"

# A silent sound device for the players (OpenAL's "null" output). Without any sound device, as on CI machines, Simple
# Voice Chat counts a player's voice chat as disabled, and Sipher sends nobody captions they can't hear.
export ALSOFT_DRIVERS=null

# start_client <loader>: that loader's player joins the server, with the recording as its microphone.
start_client() {
    local dir args
    dir="$(client "$1")"
    mkdir -p "$dir/config/voicechat"
    # No first-run screens, no sound; Sipher's welcome screen already seen (so captions are shared like a real
    # player's), and Simple Voice Chat's setup done (so its voice connection, which decides who hears whom, starts).
    printf '%s\n' onboardAccessibility:false skipMultiplayerWarning:true soundCategory_master:0.0 narrator:0 \
        tutorialStep:none maxFps:30 > "$dir/options.txt"
    printf '[captions]\n\twelcome_seen = true\n' > "$dir/config/sipher-client.toml"
    # Without its config_version, Simple Voice Chat starts the file over.
    printf 'config_version=1\nonboarding_finished=true\n' > "$dir/config/voicechat/voicechat-client.properties"
    if [ "$1" = neoforge ]; then
        # Other mods' loading warnings would stop the game at a screen that needs a click.
        printf 'showLoadWarnings = false\n' > "$dir/config/neoforge-client.toml"
        # NeoForge's early loading window only tries OpenGL, which a virtual screen can't always give 26.3 and newer;
        # the game's own window falls back to Vulkan.
        printf 'earlyWindowControl = false\n' > "$dir/config/fml.toml"
    fi
    args=(":$minecraft-$1:runClient" "${gradle_args[@]}" "-Pusername=${players[$1]}" "-PquickPlayServer=localhost:$port"
        "-PdebugAudio=$recording")
    if command -v xvfb-run > /dev/null; then
        # xvfb-run guards its X server with a cookie that only its children get: keep both for the screenshots.
        xvfb-run -a -s "-screen 0 854x480x24 +extension GLX" sh -c 'echo "$DISPLAY $XAUTHORITY" > "$0"; exec ./gradlew "$@"' \
            "$games/$node/display-$1" "${args[@]}" > "$report/$1-client-gradle.log" 2>&1 &
    else
        setsid gamescope --backend headless -W 1280 -H 720 -- env -u WAYLAND_DISPLAY \
            sh -c 'echo "$GAMESCOPE_WAYLAND_DISPLAY" > "$0"; exec ./gradlew "$@"' \
            "$games/$node/display-$1" "${args[@]}" > "$report/$1-client-gradle.log" 2>&1 &
    fi
    launchers[$1]=$!
}

# screenshot <loader> <file>
screenshot() {
    if command -v xvfb-run > /dev/null; then
        local display cookie
        read -r display cookie < "$games/$node/display-$1"
        XAUTHORITY="$cookie" import -display "$display" -window root "$2"
    else
        GAMESCOPE_WAYLAND_DISPLAY="$(cat "$games/$node/display-$1")" timeout 15 gamescopectl screenshot "$2" > /dev/null
    fi
}

# One player at a time: two Gradle builds setting up at once would trip over each other.
joined=()
if [ ${#failures[@]} -eq 0 ]; then
    for loader in "${loaders[@]}"; do
        echo "== $node: $loader player"
        start_client "$loader"
        if wait_for "$report/server-gradle.log" "${players[$loader]} joined the game" 1800 "${launchers[$loader]}" \
            "$report/$loader-client-gradle.log"; then
            joined+=("$loader")
        else
            fail "$loader player: did not join the server (see $loader-client-gradle.log)"
            break
        fi
    done
fi

# heard <loader>: the player recognised the recording (in the game: the server relaying it shows that).
heard() {
    grep -qiE "Recognised \(en\): .*$expected" "$(client "$1")/logs/latest.log"
}
# relayed <from> <to>: the server sent the speaker's recognised recording to the listener.
relayed() {
    grep -qiE "Relayed ${players[$1]}'s caption \(en\) to \[.*${players[$2]}.*\]: .*$expected" "$server/logs/latest.log"
}
# received <from> <to>: the listener got the speaker's caption.
received() {
    grep -qiE "Caption from ${players[$1]} \(en\): .*$expected" "$(client "$2")/logs/latest.log"
}
checks() {
    heard fabric && heard neoforge && relayed fabric neoforge && relayed neoforge fabric \
        && received fabric neoforge && received neoforge fabric
}
if [ ${#joined[@]} -eq 2 ]; then
    for ((i = 0; i < 120; i++)); do checks && break; sleep 1; done
    for loader in "${loaders[@]}"; do
        other=$([ "$loader" = fabric ] && echo neoforge || echo fabric)
        heard "$loader" || fail "$loader player: did not recognise \"$expected\" (see $loader-client.log)"
        relayed "$loader" "$other" || fail "server: did not relay the $loader player's caption to the $other player (see server.log)"
        received "$loader" "$other" || fail "$other player: did not receive the $loader player's caption (see $other-client.log)"
    done
    sleep 3
    for loader in "${loaders[@]}"; do
        screenshot "$loader" "$report/$loader-player.png" || fail "$loader player: could not take a screenshot"
    done
fi

for loader in "${loaders[@]}"; do
    [ -n "${launchers[$loader]:-}" ] || continue
    log="$(client "$loader")/logs/latest.log"
    grep -q "Sipher ready in" "$log" 2>/dev/null || fail "$loader player: Sipher's speech recognition did not start"
    stop_game "$(client "$loader")"
done
sleep 5
for loader in "${loaders[@]}"; do
    [ -n "${launchers[$loader]:-}" ] || continue
    pkill -TERM -P "${launchers[$loader]}" 2>/dev/null
    kill "${launchers[$loader]}" 2>/dev/null
    wait "${launchers[$loader]}" 2>/dev/null
    cp "$(client "$loader")/logs/latest.log" "$report/$loader-client.log" 2>/dev/null
done
stop_game "$server"
wait "$server_launcher" 2>/dev/null
cp "$server/logs/latest.log" "$report/server.log" 2>/dev/null

for dir in "$server" "$(client fabric)" "$(client neoforge)"; do
    if compgen -G "$dir/crash-reports/*" > /dev/null; then
        cp "$dir"/crash-reports/* "$report/"
        fail "$(basename "$(dirname "$dir")")/$(basename "$dir"): crashed (crash report in the report)"
    fi
done
# Errors from Sipher's own logger: "(Sipher)" in Fabric's log format, "[Sipher/]" in NeoForge's.
if grep -lE '/ERROR\] (\(Sipher\)|\[Sipher/\])' "$report"/*.log 2>/dev/null; then
    fail "Sipher logged errors (in the logs listed above)"
fi

recognised=$(grep -oE "Relayed .*" "$report/server.log" 2>/dev/null | grep -iE "$expected" | tail -1 | sed 's/^[^:]*: //')
if [ ${#failures[@]} -eq 0 ]; then
    summary="✅ **$node** server: the Fabric and the NeoForge player heard each other (\"${recognised}\")"
else
    summary="❌ **$node** server: $(IFS=';'; echo "${failures[*]}")"
fi
echo "$summary"
[ -n "${GITHUB_STEP_SUMMARY:-}" ] && echo "- $summary" >> "$GITHUB_STEP_SUMMARY"
[ ${#failures[@]} -eq 0 ]
