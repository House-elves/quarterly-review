# quarterly-review

A house elf that writes your quarterly accomplishments from your GitHub work: merged PRs and closed issues in the
quarter, plus the weekly statuses you sent in it. Claude writes it in the shape of the "Quarterly Accomplishments"
doc: PTO, Presentations, then a section per project with short "Area: what" bullets.

    ./install.sh
    quarterly-review                      # the last quarter that ended, printed
    quarterly-review --quarter=2026-Q3 --save   # saved for Elf Desk's Quarterly tab

Elf Desk runs it through the runner (mode `quarterly`) when you click Write the review. It never sends or posts.
Reviews are saved in `~/.config/quarterly-review/runs/`.
