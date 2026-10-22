# KaTTY's shell integration for bash: it tells the terminal where the prompts are (OSC 133), and which directory the shell is
# in (OSC 7).
#
# KaTTY starts bash with `--rcfile` pointing here, which an interactive shell reads instead of ~/.bashrc. This file then reads
# what bash would have read (the files of a login shell when KaTTY was asked for one, else ~/.bashrc), and only then installs its
# hooks, so that what the user's files did to the prompt is what it works on. It works with bash 3.2, which is the one of macOS:
# `--posix` with ENV, which other terminals use, is not read by that version.
#
# A: a prompt starts. B: what the user types starts. C: the command runs, and its output starts. D;status: it ended.

if [[ -n "${KATTY_BASH_INJECTED-}" ]]; then
    builtin unset KATTY_BASH_INJECTED

    if [[ -n "${KATTY_BASH_LOGIN-}" ]]; then
        builtin unset KATTY_BASH_LOGIN
        __katty_login=1
        [[ -r /etc/profile ]] && builtin source /etc/profile
        for __katty_rc in "$HOME/.bash_profile" "$HOME/.bash_login" "$HOME/.profile"; do
            if [[ -r "$__katty_rc" ]]; then
                builtin source "$__katty_rc"
                break
            fi
        done
        builtin unset __katty_rc
    else
        [[ -r "$HOME/.bashrc" ]] && builtin source "$HOME/.bashrc"
    fi
fi

# Only for the shells that a person types in.
[[ $- == *i* ]] || return 0

__katty_command_running=""
__katty_in_prompt=""
__katty_reported_pwd=""
__katty_reported_url=""

# The directory as a file:// URL, which is what OSC 7 carries. It goes by bytes, so that what is not ASCII is encoded as UTF-8.
__katty_report_pwd() {
    if [[ "$PWD" != "$__katty_reported_pwd" ]]; then
        local LC_ALL=C
        local encoded="" char i
        for (( i = 0; i < ${#PWD}; ++i )); do
            char="${PWD:i:1}"
            case "$char" in
                [a-zA-Z0-9/._~-]) encoded+="$char" ;;
                *) encoded+="$(builtin printf '%%%02X' "'$char")" ;;
            esac
        done
        __katty_reported_pwd="$PWD"
        __katty_reported_url="file://${HOSTNAME}${encoded}"
    fi
    builtin printf '\e]7;%s\a' "$__katty_reported_url"
}

# The first of the prompt command: $? is still the status of the command that ended.
__katty_prompt_start() {
    local last_status=$?
    __katty_in_prompt=1
    if [[ -n "$__katty_command_running" ]]; then
        builtin printf '\e]133;D;%s\a' "$last_status"
        __katty_command_running=""
    fi
    builtin printf '\e]133;A\a'
    __katty_report_pwd
    return "$last_status"
}

# The last of it, after what the user's prompt command did to the prompt.
__katty_prompt_end() {
    local last_status=$?
    # The prompt holds it as the text that bash expands, not as the characters.
    if [[ "$PS1" != *'133;B'* ]]; then
        PS1="${PS1}\\[\\e]133;B\\a\\]"
    fi
    __katty_in_prompt=""
    return "$last_status"
}

__katty_preexec() {
    # What the prompt command and the completion run is not the user's command, and neither is what follows its first command.
    # The trap runs before the prompt command's own functions, when the prompt is not marked yet.
    [[ -n "$__katty_in_prompt" || -n "${COMP_LINE-}" || -n "$__katty_command_running" || "$BASH_COMMAND" == __katty_* ]] && return
    __katty_command_running=1
    builtin printf '\e]133;C\a'
}

# A login shell that KaTTY started is not one for bash, which was started with --rcfile: `logout` would refuse ("not login shell"),
# and ~/.bash_logout would not be read. Both are given back: `logout` ends the shell, and the shell that ends reads the file, before
# what an EXIT trap that was already there does.
if [[ -n "${__katty_login-}" ]]; then
    logout() { builtin exit "$@"; }

    __katty_exit_trap="$(builtin trap -p EXIT)"
    __katty_exit_trap="${__katty_exit_trap#trap -- }"
    __katty_exit_trap="${__katty_exit_trap% EXIT}"
    # What `trap -p` prints is quoted for the shell: this gives back the command.
    builtin eval "__katty_exit_trap=${__katty_exit_trap:-''}"

    __katty_logout() {
        [[ -r "$HOME/.bash_logout" ]] && builtin source "$HOME/.bash_logout"
        [[ -n "$__katty_exit_trap" ]] && builtin eval "$__katty_exit_trap"
    }
    builtin trap '__katty_logout' EXIT
fi

if [[ "$(builtin declare -p PROMPT_COMMAND 2>/dev/null)" == "declare -a"* ]]; then
    PROMPT_COMMAND=(__katty_prompt_start "${PROMPT_COMMAND[@]}" __katty_prompt_end)
elif [[ -n "${PROMPT_COMMAND-}" ]]; then
    PROMPT_COMMAND="__katty_prompt_start"$'\n'"${PROMPT_COMMAND}"$'\n'"__katty_prompt_end"
else
    PROMPT_COMMAND="__katty_prompt_start"$'\n'"__katty_prompt_end"
fi

# A DEBUG trap that is already there is the user's: the start of the output is not marked rather than replaced.
if [[ -z "$(builtin trap -p DEBUG)" ]]; then
    builtin trap '__katty_preexec' DEBUG
fi
