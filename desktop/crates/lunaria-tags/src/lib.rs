//! Safe atomic audio metadata & cover art writer with Windows File-Lock Handover Protocol.

#![forbid(unsafe_code)]

mod error;
#[cfg(test)]
mod tests;
mod writer;

pub use error::TagError;
pub use writer::{
    CoverArtEdit, TagEditRequest, TagWriteOutcome, save_track_tags, write_tags_to_file,
};
