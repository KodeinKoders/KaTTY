# KaTTY's shell integration for zsh.
#
# KaTTY starts zsh with ZDOTDIR pointing here, so that this file is the first one that zsh reads. It puts ZDOTDIR back to
# what it was, reads the user's own .zshenv as zsh would have, and loads the integration: the user's other files (.zshrc...)
# are read from where they are, as usual.
if [[ -n "${KATTY_ZDOTDIR+X}" ]]; then
    builtin export ZDOTDIR="$KATTY_ZDOTDIR"
    builtin unset KATTY_ZDOTDIR
else
    builtin unset ZDOTDIR
fi

{
    # The directory of this file, which the integration lives in, before ZDOTDIR is lost.
    _katty_integration_dir="${${(%):-%x}:A:h}"
    if [[ -r "${ZDOTDIR:-$HOME}/.zshenv" ]]; then
        builtin source -- "${ZDOTDIR:-$HOME}/.zshenv"
    fi
} always {
    if [[ -o interactive && -r "$_katty_integration_dir/katty-integration.zsh" ]]; then
        builtin source -- "$_katty_integration_dir/katty-integration.zsh"
    fi
    builtin unset _katty_integration_dir
}
