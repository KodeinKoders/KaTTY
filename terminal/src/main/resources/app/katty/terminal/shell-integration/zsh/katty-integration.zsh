# KaTTY's shell integration for zsh: it tells the terminal where the prompts are (OSC 133), and which directory the shell is
# in (OSC 7).
#
# A: a prompt starts. B: what the user types starts. C: the command runs, and its output starts. D;status: it ended.

autoload -Uz add-zsh-hook

typeset -g _katty_command_running=""
typeset -g _katty_reported_pwd=""
typeset -g _katty_reported_url=""

# The directory as a file:// URL, which is what OSC 7 carries. It goes by bytes, so that what is not ASCII is encoded as UTF-8.
_katty_report_pwd() {
    if [[ "$PWD" != "$_katty_reported_pwd" ]]; then
        local LC_ALL=C
        local encoded="" char
        local i
        for (( i = 1; i <= ${#PWD}; ++i )); do
            char="${PWD[i]}"
            case "$char" in
                [a-zA-Z0-9/._~-]) encoded+="$char" ;;
                *) encoded+="$(builtin printf '%%%02X' "'$char")" ;;
            esac
        done
        _katty_reported_pwd="$PWD"
        _katty_reported_url="file://${HOST}${encoded}"
    fi
    builtin printf '\e]7;%s\a' "$_katty_reported_url"
}

_katty_precmd() {
    # The first thing: the status of the command that ended.
    local last_status=$?
    if [[ -n "$_katty_command_running" ]]; then
        builtin printf '\e]133;D;%s\a' "$last_status"
        _katty_command_running=""
    fi
    builtin printf '\e]133;A\a'
    _katty_report_pwd

    # The end of the prompt is marked in the prompt itself, so that it is where the cursor is when the user can type. A theme
    # that builds the prompt in its own precmd has done it by now: this hook moves itself to the end for the next prompts.
    if [[ "$PS1" != *$'\e]133;B\a'* ]]; then
        PS1="${PS1}%{"$'\e]133;B\a'"%}"
    fi
    precmd_functions=(${precmd_functions:#_katty_precmd} _katty_precmd)
}

_katty_preexec() {
    _katty_command_running=1
    builtin printf '\e]133;C\a'
}

add-zsh-hook precmd _katty_precmd
add-zsh-hook preexec _katty_preexec
add-zsh-hook chpwd _katty_report_pwd
