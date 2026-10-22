# KaTTY's shell integration for fish: it tells the terminal where the prompts are (OSC 133), and which directory the shell is in
# (OSC 7).
#
# A: a prompt starts. C: the command runs, and its output starts. D;status: it ended. fish has no PS1 to put a mark for where what the
# user types starts (B) in, so there is none.
#
# It is adapted from the integration of Ghostty (https://github.com/ghostty-org/ghostty, MIT license, Copyright (c) 2024 Mitchell
# Hashimoto, Ghostty contributors).
#
# KaTTY starts fish with its directory in front of XDG_DATA_DIRS, and fish reads every file of fish/vendor_conf.d of the directories
# in it before the user's own configuration: this is one. Nothing of the user's is replaced or edited.

# Only a shell that is typed in.
status --is-interactive; or exit 0

# What the user's programs see is what it was before KaTTY: the directory that started this is taken out of XDG_DATA_DIRS, or the
# variable, when there was none and KaTTY made it up.
if set -q KATTY_SHELL_INTEGRATION_XDG_UNSET
    set --erase XDG_DATA_DIRS
    set --erase KATTY_SHELL_INTEGRATION_XDG_UNSET
    set --erase KATTY_SHELL_INTEGRATION_XDG_DIR
else if set -q KATTY_SHELL_INTEGRATION_XDG_DIR
    set --local data_dirs (string split --no-empty : -- $XDG_DATA_DIRS)
    if set --local index (contains --index -- $KATTY_SHELL_INTEGRATION_XDG_DIR $data_dirs)
        set --erase data_dirs[$index]
        if test (count $data_dirs) -gt 0
            set --global --export XDG_DATA_DIRS (string join : -- $data_dirs)
        else
            set --erase XDG_DATA_DIRS
        end
    end
    set --erase KATTY_SHELL_INTEGRATION_XDG_DIR
end

# It is set up at the first prompt, which is after the user's own configuration, so that what they define does not replace it.
function __katty_setup --on-event fish_prompt -d "Set up KaTTY's shell integration"
    functions --erase __katty_setup

    # The directory as a file:// URL, which is what OSC 7 carries.
    function __katty_report_pwd --on-variable PWD -d "Tell the terminal which directory the shell is in"
        printf '\e]7;file://%s%s\a' (hostname) (string escape --style=url -- $PWD)
    end

    # What was the last thing that was marked, to mark what is missing: a command that was started and did not end (the shell was
    # interrupted) is ended by the next prompt, and nothing is ended that was not started.
    function __katty_mark_prompt_start --on-event fish_prompt --on-event fish_cancel --on-event fish_posterror
        if test "$__katty_prompt_state" = pre-exec
            printf '\e]133;D\a'
        end
        set --global __katty_prompt_state prompt-start
        printf '\e]133;A\a'
    end

    function __katty_mark_output_start --on-event fish_preexec
        set --global __katty_prompt_state pre-exec
        printf '\e]133;C\a'
    end

    function __katty_mark_output_end --on-event fish_postexec
        # First: setting a variable is a command, and has its own status.
        set --local last_status $status
        set --global __katty_prompt_state post-exec
        printf '\e]133;D;%s\a' $last_status
    end

    # The first prompt is the one that is being drawn: the handlers above were defined too late to be called for it.
    __katty_report_pwd
    __katty_mark_prompt_start
end
