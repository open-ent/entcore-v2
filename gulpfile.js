// const {task, src, dest, series} = require('gulp');
const gulp = require('gulp');
const path = require('path');
const rev = require('gulp-rev');
const clean = require('gulp-clean');
const fs = require('fs');
const replace = require('gulp-replace');

// const adminBuild = require('./gulpfile-admin').adminBuild;

let apps = ['archive', 'auth', 'directory', 'timeline', 'workspace'];

let i = process.argv.indexOf("--module");
// check if a module is specified and if it matches one of apps
if (i > -1 && process.argv.length > (i+1) && apps.indexOf(process.argv[i+1]) > -1) {
    apps = [process.argv[i+1]];
}

// --springboard=<chemin> (yargs n'était qu'une dépendance transitive de gulp 4)
function argValue(name) {
    const prefix = '--' + name + '=';
    const inline = process.argv.find(a => a.startsWith(prefix));
    if (inline) return inline.slice(prefix.length);
    const idx = process.argv.indexOf('--' + name);
    return idx > -1 ? process.argv[idx + 1] : undefined;
}

function distDir(a) {
    return './' + a + '/src/main/resources/public/dist';
}

// Appel direct du webpack 1.x embarqué par webpack-stream (celui qui a toujours produit les
// bundles : les webpack.config.js sont au format webpack 1) : les flux « through » de
// webpack-stream ne se raccordent pas à ceux de gulp 5. Une erreur de compilation fait
// échouer le build, comme le faisait la propagation d'erreur de merge2 sous gulp 4.
function webpackApp(a, mode) {
    return function webpackBundle(done) {
        const webpackConf = require('./' + a + '/webpack.config.js');
        if (mode === 'dev') {
            webpackConf.devtool = 'inline-source-map';
        }
        webpackConf.output.path = path.resolve(__dirname, distDir(a));
        require('webpack-stream').webpack(webpackConf, (err, stats) => {
            if (stats) console.log(stats.toString({ colors: false, chunks: false }));
            if (!err && stats && stats.hasErrors()) {
                err = new Error(a + ' : erreurs de compilation webpack');
            }
            done(err);
        });
    };
}

// Copies empreintées (application-<hash>.js…) et rev-manifest.json, comme le faisait
// gulp-rev en aval de webpack-stream.
function revApp(a) {
    return function revBundle() {
        return gulp.src(distDir(a) + '/**/*.+(js|map)')
            .pipe(rev())
            .pipe(gulp.dest(distDir(a)))
            .pipe(rev.manifest('./' + a + '/rev-manifest.json', { merge: true }))
            .pipe(gulp.dest('./'));
    };
}

function startWebpack(mode) {
    return gulp.parallel(apps.map(a => gulp.series(webpackApp(a, mode), revApp(a))));
}

function updateRefsApp(a) {
    return function updateRefs() {
        return gulp.src('./' + a + '/src/main/resources/view-src/**/*.+(html|txt|json)')
            .pipe(replace('@@VERSION', Date.now()))
            .pipe(gulp.dest('./' + a + '/src/main/resources/view'));
    };
}

function updateRefs() {
    console.log('Updating hashs in views');
    return gulp.parallel(apps.map(updateRefsApp));
}

function dropOldFiles() {
    return gulp.parallel(apps.map(a => function dropDist() {
        return gulp.src([distDir(a)], { read: false, "allowEmpty": true })
            .pipe(clean());
    }));
};

function updateLibs(){
    return gulp.src('./node_modules/pixi.js/dist/pixi.min.js')
        .pipe(gulp.dest('./infra/src/main/resources/public/js'));
}

function copyBehaviours() {
    return gulp.parallel(apps.map(a => function copyBehavioursApp() {
        return gulp.src(distDir(a) + '/behaviours.js', {allowEmpty: true})
            .pipe(gulp.dest('./' + a + '/src/main/resources/public/js'));
    }));
}

gulp.task('drop-old-files', dropOldFiles());
gulp.task('update-libs', updateLibs);
gulp.task('webpack', startWebpack());
gulp.task('webpack-dev', startWebpack('dev'));
gulp.task('build', gulp.series('drop-old-files', 'update-libs', 'webpack', gulp.parallel(updateRefs(), copyBehaviours())));


function getModName(fileContent, app){
    return fileContent.trim();
}

let springboardPath = '../recette';
if (argValue('springboard')) {
    springboardPath = argValue('springboard');
    console.log('Using springboard at ' + springboardPath);
}

apps.forEach((app) => {
    gulp.task('watch-' + app, () => {
        gulp.watch('./' + app + '/src/main/resources/public/ts/**/*.ts', gulp.series(webpackApp(app, 'dev'), revApp(app)));

        fs.readFile("./.version.properties", "utf8", function(error, content){
            var modName = getModName(content, app);
            // encoding: false : gulp 5 lit en UTF-8 par défaut et corromprait les binaires.
            var copyToSpringboard = () => {
                console.log('Copying resources to ' + springboardPath + '/mods/' + modName);
                return gulp.src('./' + app + '/src/main/resources/**/*', { encoding: false })
                    .pipe(gulp.dest(springboardPath + '/mods/' + modName));
            };
            gulp.watch(['./' + app + '/src/main/resources/public/template/**/*.html', '!./' + app + '/src/main/resources/public/template/entcore/*.html'], copyToSpringboard);
            gulp.watch('./' + app + '/src/main/resources/view/**/*.html', copyToSpringboard);
            gulp.watch(['./' + app + '/src/main/resources/public/dist/**/*.js', '!./' + app + '/src/main/resources/public/dist/entcore/**/*.js'], copyToSpringboard);
            gulp.watch('./' + app + '/rev-manifest.json', updateRefsApp(app));
        });
    });
})
