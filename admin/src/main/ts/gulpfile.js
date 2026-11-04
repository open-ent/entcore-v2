var gulp = require('gulp')
var rename = require('gulp-rename');
var clean = require('gulp-clean');

// encoding: false : gulp 5 lit les fichiers en UTF-8 par défaut et corromprait en silence
// les binaires du dist Angular (polices, images).
function copy(srcDir, destDir) {
    console.log(`Copying ${srcDir} to ${destDir}...`);
    return gulp.src(srcDir, { encoding: false })
        .pipe(gulp.dest(destDir));
}

function renameIndexFile(srcIndexFile, newIndexFileBasename, destDir) {
    console.log(`Renaming ${srcIndexFile} to ${destDir}${newIndexFileBasename}`);
    return gulp.src(srcIndexFile)
        .pipe(rename(function (path) { path.basename = newIndexFileBasename; }))
        .pipe(gulp.dest(destDir));
}

gulp.task('clean', () => {
    return gulp.src('../resources/public/dist', {read: false, allowEmpty: true})
        .pipe(clean({force: true}));
});

gulp.task('cleanWithDockerPath', () => {
    return gulp.src('/home/node/base/src/main/resources/public/dist', {read: false, allowEmpty: true})
        .pipe(clean({force: true}));
});

// Les deux copies sont des tâches à part entière : gulp attend leur fin (la copie du dist
// n'était auparavant pas attendue).
gulp.task('build', gulp.series('clean', gulp.parallel(
    function copyDist() { return copy('./dist/**/*', '../resources/public/dist/'); },
    function copyIndex() { return renameIndexFile('./dist/index.html', 'admin', '../resources/view/'); }
)));

gulp.task('buildWithDockerPath', gulp.series('cleanWithDockerPath', gulp.parallel(
    function copyDist() { return copy('/home/node/app/dist/**/*', '/home/node/base/src/main/resources/public/dist/'); },
    function copyIndex() { return renameIndexFile('/home/node/app/dist/index.html', 'admin', '/home/node/base/src/main/resources/view/'); }
)));
