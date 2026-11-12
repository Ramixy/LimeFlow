#![no_std]
//! Same macro as windows-link 0.2, but symbols resolve through the MinGW
//! import libraries that the linker adds by default (kernel32, advapi32, ...).

#[macro_export]
macro_rules! link {
    ($library:literal $abi:literal $($link_name:literal)? fn $($function:tt)*) => (
        extern $abi {
            $(#[link_name=$link_name])?
            pub fn $($function)*;
        }
    )
}
