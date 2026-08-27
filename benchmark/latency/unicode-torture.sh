#!/usr/bin/env bash
# Every construct the text pass special-cases, on one screen.
#
# The fast text path (blank batching + the ASCII probe skip) is the one change here that can
# fail silently: it produces a WRONG PICTURE rather than a wrong value, so no unit test sees
# it. This fixture exists to be rendered twice, with the fast path off and on, and the two
# frames compared pixel for pixel.
#
# Deliberately heavy on runs of spaces, because that is exactly what blank batching changes:
# aligned columns, indentation, and trailing gaps under an underline.
printf '\033[2J\033[H'
echo "ascii        the quick brown fox jumps over the lazy dog 0123456789"
echo "aligned      name          size     modified          mode"
echo "aligned      src/           4096     Aug 26 10:11      drwxr-xr-x"
echo "aligned      README.md      1024     Aug 26 10:12      -rw-r--r--"
printf 'zwj          \xF0\x9F\x91\xA8\xE2\x80\x8D\xF0\x9F\x92\xBB family \xF0\x9F\x91\xA9\xE2\x80\x8D\xF0\x9F\x91\xA9\xE2\x80\x8D\xF0\x9F\x91\xA7 end\n'
printf 'flags        \xF0\x9F\x87\xBA\xF0\x9F\x87\xB8 \xF0\x9F\x87\xAF\xF0\x9F\x87\xB5 \xF0\x9F\x87\xAE\xF0\x9F\x87\xB3 end\n'
printf 'skintone     \xF0\x9F\x91\x8B\xF0\x9F\x8F\xBD \xF0\x9F\x91\x8D\xF0\x9F\x8F\xBF end\n'
printf 'varsel       \xE2\x9C\x94\xEF\xB8\x8F \xE2\x9A\xA0\xEF\xB8\x8F \xE2\x9D\xA4\xEF\xB8\x8F end\n'
printf 'cjk          \xE4\xBD\xA0\xE5\xA5\xBD\xE4\xB8\x96\xE7\x95\x8C  \xE3\x81\x93\xE3\x82\x93\xE3\x81\xAB\xE3\x81\xA1\xE3\x81\xAF end\n'
printf 'powerline    \xEE\x82\xB0 \xEE\x82\xB1 \xEE\x82\xB2 \xEE\x82\xB3 end\n'
printf 'underline    \033[4munderlined   with   gaps\033[0m and \033[4mtrailing    \033[0m|\n'
printf 'bold/italic  \033[1mbold   text\033[0m \033[3mitalic   text\033[0m \033[1;3mboth\033[0m\n'
printf 'truecolor    '
for i in 0 1 2 3 4 5 6 7; do printf '\033[38;2;%d;%d;%dm block   \033[0m' $((i*31)) $((255-i*31)) $((i*17)); done
printf '\n'
printf 'inverse      \033[7m inverse   with   spaces \033[0m end\n'
printf 'bg runs      \033[41m red   \033[42m green   \033[44m blue   \033[0m end\n'
printf 'trailing     text with trailing spaces        \n'
printf 'leading              indented by many spaces\n'
printf 'dim          \033[2mdim   text   here\033[0m end\n'
echo
