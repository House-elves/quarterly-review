<img src="https://raw.githubusercontent.com/House-elves/elf-desk/main/docs/brand/mark.svg" width="72" alt="House Elves logo: a checkmark that ends in an elf's hat">

# quarterly-review

A house elf that writes your quarterly accomplishments from your GitHub work: merged PRs and closed issues in the
quarter, plus the weekly statuses you sent in it. Claude writes it in the shape of the "Quarterly Accomplishments"
doc: PTO, Presentations, then a section per project with short "Area: what" bullets.

The easy way: add it in [Elf Desk](https://github.com/House-elves/elf-desk) with the **+** after the last tab, which reads
[`elf.yaml`](elf.yaml) and asks for the settings. By hand:

    ./install.sh
    quarterly-review                      # the last quarter that ended, printed
    quarterly-review --quarter=2026-Q3 --save   # saved for its tab on Elf Desk

On Elf Desk this elf brings the **Quarterly** tab, drawn by [`ui/quarterly.js`](ui/quarterly.js) in the desk's
sandboxed frame: **Write the review** runs it with the quarter you picked. It never sends or posts.
Reviews are saved in `~/.config/quarterly-review/runs/`.
